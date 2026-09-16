package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.application.workspace.WorkspaceDirectoryService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.AgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.InContainerAgentGatewayClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class InContainerSessionBindingServiceTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<AgentSessionRepository>(relaxed = true)
    private val gateway = mockk<InContainerAgentGatewayClient>()
    private val sessionStatus = mockk<SessionStatusPublisher>(relaxed = true)
    private val directories = mockk<WorkspaceDirectoryService>()
    private val service = InContainerSessionBindingService(workspaces, sessions, gateway, sessionStatus, directories)

    private val workspaceId = WorkspaceId.random()
    private val sessionId = AgentSessionId.random()
    private val workspace = scratchWorkspace()

    @Test
    fun `start spawns a shell agent and binds the session RUNNING`() {
        every { workspaces.findById(workspaceId) } returns workspace
        every { gateway.spawnAgent(any()) } returns
            AgentGatewayClient.GatewayAgent(id = "abc12345", kind = WorkspaceAgentKind.SHELL, cwd = "/workspaces/x")
        val saved = slot<AgentSession>()
        every { sessions.save(capture(saved)) } answers { firstArg() }

        val result =
            service.start(
                StartRunnerSessionBindingInput(
                    workspaceId = workspaceId,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.SHELL,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        assertThat(saved.captured.status).isEqualTo(AgentSessionStatus.RUNNING)
        assertThat(saved.captured.gatewayAgentId).isEqualTo("abc12345")
        verify { sessionStatus.publishStatus(saved.captured) }
    }

    @Test
    fun `start rejects a non-Shell kind`() {
        every { workspaces.findById(workspaceId) } returns workspace

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            service.start(
                StartRunnerSessionBindingInput(
                    workspaceId = workspaceId,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.CLAUDE,
                ),
            )
        }
    }

    @Test
    fun `start on a persistence failure stops the spawned agent and reports Unavailable`() {
        every { workspaces.findById(workspaceId) } returns workspace
        every { gateway.spawnAgent(any()) } returns
            AgentGatewayClient.GatewayAgent(id = "abc12345", kind = WorkspaceAgentKind.SHELL, cwd = "/workspaces/x")
        every { sessions.save(any()) } throws IllegalStateException("boom")

        val result =
            service.start(
                StartRunnerSessionBindingInput(
                    workspaceId = workspaceId,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.SHELL,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Unavailable::class.java)
        verify { gateway.stopAgent(workspace, "abc12345") }
    }

    @Test
    fun `ensureBound is Bound when the session is already RUNNING and bound`() {
        every { sessions.findById(sessionId) } returns runningSession()
        every { workspaces.findById(workspaceId) } returns workspace
        every { directories.directoryFor(workspaceId) } returns Path.of("/workspaces/$workspaceId")

        val result = service.ensureBound(EnsureRunnerSessionBoundInput(sessionId = sessionId))

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
    }

    @Test
    fun `ensureBound is Unavailable when the tmux process was lost`() {
        every { sessions.findById(sessionId) } returns runningSession().clearGatewayBinding()
        every { workspaces.findById(workspaceId) } returns workspace

        val result = service.ensureBound(EnsureRunnerSessionBoundInput(sessionId = sessionId))

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Unavailable::class.java)
    }

    @Test
    fun `restart is not implemented in this scope`() {
        org.junit.jupiter.api.assertThrows<UnsupportedOperationException> {
            service.restart(
                RestartRunnerSessionBindingInput(
                    workspaceId = workspaceId,
                    sessionId = sessionId,
                    expectedGeneration = 1,
                ),
            )
        }
    }

    private fun scratchWorkspace() =
        Workspace(
            id = workspaceId,
            name = "playground",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.PENDING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            kind = WorkspaceKind.SCRATCH,
        )

    private fun runningSession() =
        AgentSession(
            id = sessionId,
            workspaceId = workspaceId,
            kind = WorkspaceAgentKind.SHELL,
            gatewayAgentId = "abc12345",
            status = AgentSessionStatus.RUNNING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RestartAgentSessionServiceTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val binding = mockk<RunnerSessionBindingService>()
    private val clock = Clock.fixed(Instant.parse("2026-06-12T09:00:00Z"), ZoneOffset.UTC)
    private val service = RestartAgentSessionService(workspaces, sessions, binding, clock)

    @Test
    fun `restart delegates running interactive session with expected generation`() {
        val ws = workspace()
        val session = session(ws.id, status = WorkspaceAgentSessionStatus.RUNNING).copy(generation = 4)
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session
        every {
            binding.restart(
                RestartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    expectedGeneration = 4,
                    reason = "manual",
                ),
            )
        } returns bound(ws, session)

        val result =
            service.restart(
                RestartAgentSessionInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    expectedGeneration = 4,
                    reason = "manual",
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        verify { binding.restart(any()) }
    }

    @Test
    fun `restart allows retained stopped session before cleanup expiry`() {
        val ws = workspace()
        val session =
            session(ws.id, status = WorkspaceAgentSessionStatus.STOPPED)
                .copy(retainedUntil = Instant.parse("2026-06-12T10:00:00Z"))
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session
        every { binding.restart(any()) } returns bound(ws, session)

        service.restart(RestartAgentSessionInput(workspaceId = ws.id, sessionId = session.id))

        verify { binding.restart(any()) }
    }

    @Test
    fun `restart rejects expired stopped session`() {
        val ws = workspace()
        val session =
            session(ws.id, status = WorkspaceAgentSessionStatus.STOPPED)
                .copy(retainedUntil = Instant.parse("2026-06-12T08:59:59Z"))
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session

        assertThrows<IllegalArgumentException> {
            service.restart(RestartAgentSessionInput(workspaceId = ws.id, sessionId = session.id))
        }
    }

    @Test
    fun `restart rejects non-interactive sessions`() {
        val ws = workspace()
        val session =
            session(ws.id, status = WorkspaceAgentSessionStatus.RUNNING)
                .copy(runMode = "HEADLESS")
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session

        assertThrows<IllegalArgumentException> {
            service.restart(RestartAgentSessionInput(workspaceId = ws.id, sessionId = session.id))
        }
    }

    @Test
    fun `restart rejects sessions from a different workspace`() {
        val ws = workspace()
        val session = session(WorkspaceId.random(), status = WorkspaceAgentSessionStatus.RUNNING)
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session

        assertThrows<IllegalArgumentException> {
            service.restart(RestartAgentSessionInput(workspaceId = ws.id, sessionId = session.id))
        }
    }

    @Test
    fun `restart returns conflict when expected generation is stale`() {
        val ws = workspace()
        val session = session(ws.id, status = WorkspaceAgentSessionStatus.RUNNING).copy(generation = 9)
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session

        val result =
            service.restart(
                RestartAgentSessionInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    expectedGeneration = 8,
                ),
            )

        assertThat(result).isEqualTo(RunnerSessionBindingResult.Conflict(current = session))
    }

    private fun bound(
        workspace: Workspace,
        session: WorkspaceAgentSession,
    ) = RunnerSessionBindingResult.Bound(
        workspace = workspace,
        session = session,
        gatewayAgent =
            AgentGatewayClient.GatewayAgent(
                id = "abc12345",
                kind = session.kind,
                cwd = "/workspace",
            ),
        provisioning = RunnerProvisioningResult.AlreadyReady,
    )

    private fun workspace() =
        Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = "agent-runner-abcdef01",
            pvcName = "workspace-abcdef01",
            gatewayEndpoint = "http://x:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun session(
        workspaceId: WorkspaceId,
        status: WorkspaceAgentSessionStatus,
    ) = WorkspaceAgentSession(
        id = WorkspaceAgentSessionId.random(),
        workspaceId = workspaceId,
        kind = WorkspaceAgentKind.CLAUDE,
        gatewayAgentId = "abc12345",
        status = status,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}

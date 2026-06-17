package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.rag.ContextBuilder
import com.jorisjonkers.personalstack.agents.application.sessionbinding.EnsureRunnerSessionBoundInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerProvisioningResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.TurnRole
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.TurnRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class SendUserInputCommandHandlerTest {
    private val turns = mockk<TurnRepository>(relaxed = true)
    private val gateway = mockk<AgentGatewayClient>(relaxed = true)
    private val contextBuilder =
        mockk<ContextBuilder> {
            every { augment(any()) } answers { firstArg() }
        }
    private val binding = mockk<RunnerSessionBindingService>()
    private val handler = SendUserInputCommandHandler(turns, gateway, contextBuilder, binding)

    @Test
    fun `handle persists user turn and forwards input to the current gateway binding`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = "abc12345")
        every { binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = session.id)) } returns
            bound(ws, session)
        val savedTurn = slot<Turn>()
        every { turns.save(capture(savedTurn)) } answers { savedTurn.captured }

        handler.handle(SendUserInputCommand(sessionId = session.id, text = "hello", enter = true))

        assertThat(savedTurn.captured.role).isEqualTo(TurnRole.USER)
        assertThat(savedTurn.captured.body).isEqualTo("hello")
        verify { gateway.sendInput(ws, "abc12345", "hello", true) }
    }

    @Test
    fun `handle forwards augmented prompt while persisting raw user text`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = "abc12345")
        every { binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = session.id)) } returns
            bound(ws, session)
        val savedTurn = slot<Turn>()
        every { turns.save(capture(savedTurn)) } answers { savedTurn.captured }
        every { contextBuilder.augment("hello") } returns "<context>kb hit</context>\n\nhello"

        handler.handle(SendUserInputCommand(sessionId = session.id, text = "hello"))

        assertThat(savedTurn.captured.body).isEqualTo("hello")
        verify { gateway.sendInput(ws, "abc12345", "<context>kb hit</context>\n\nhello", true) }
    }

    @Test
    fun `handle throws AgentRunnerUnavailableException when ensureBound returns Unavailable`() {
        val session = session(WorkspaceId.random(), gatewayAgentId = null)
        every {
            binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = session.id))
        } returns
            RunnerSessionBindingResult.Unavailable(
                workspaceId = session.workspaceId,
                runnerStatus = RunnerUnavailableReason.BOOT_LEASE_HELD.label,
                retryAfterSeconds = 10,
            )

        val ex =
            assertThrows<AgentRunnerUnavailableException> {
                handler.handle(SendUserInputCommand(sessionId = session.id, text = "hello", enter = true))
            }

        assertThat(ex.runnerStatus).isEqualTo(RunnerUnavailableReason.BOOT_LEASE_HELD.label)
        assertThat(ex.retryAfterSeconds).isEqualTo(10)
    }

    private fun bound(
        workspace: Workspace,
        session: WorkspaceAgentSession,
    ) = RunnerSessionBindingResult.Bound(
        workspace = workspace,
        session = session,
        gatewayAgent =
            AgentGatewayClient.GatewayAgent(
                id = session.gatewayAgentId ?: "abc12345",
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
            podName = null,
            pvcName = null,
            gatewayEndpoint = "http://x:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun session(
        workspaceId: WorkspaceId,
        gatewayAgentId: String?,
    ) = WorkspaceAgentSession(
        id = WorkspaceAgentSessionId.random(),
        workspaceId = workspaceId,
        kind = WorkspaceAgentKind.CLAUDE,
        gatewayAgentId = gatewayAgentId,
        status = WorkspaceAgentSessionStatus.RUNNING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}

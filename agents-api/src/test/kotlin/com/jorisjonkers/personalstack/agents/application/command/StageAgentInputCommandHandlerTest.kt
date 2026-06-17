package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.sessionbinding.EnsureRunnerSessionBoundInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerProvisioningResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class StageAgentInputCommandHandlerTest {
    private val binding = mockk<RunnerSessionBindingService>()
    private val gateway = mockk<AgentGatewayClient>()
    private val handler = StageAgentInputCommandHandler(binding, gateway)

    @Test
    fun `handle stages input through current runner binding`() {
        val ws = workspace()
        val session = session(ws.id)
        every {
            binding.ensureBound(
                EnsureRunnerSessionBoundInput(
                    sessionId = session.id,
                    workspaceId = ws.id,
                ),
            )
        } returns bound(ws, session)
        every { gateway.stageInput(ws, "abc12345", "payload", "notes.txt") } returns
            AgentGatewayClient.StagedInput(path = "/tmp/notes.txt", bytes = 7, name = "notes.txt")

        val staged =
            handler.handle(
                StageAgentInputCommand(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    content = "payload",
                    name = "notes.txt",
                ),
            )

        assertThat(staged.path).isEqualTo("/tmp/notes.txt")
        verify { gateway.stageInput(ws, "abc12345", "payload", "notes.txt") }
    }

    @Test
    fun `handle throws AgentRunnerUnavailableException when ensureBound returns Unavailable`() {
        val ws = workspace()
        val session = session(ws.id)
        every {
            binding.ensureBound(
                EnsureRunnerSessionBoundInput(
                    sessionId = session.id,
                    workspaceId = ws.id,
                ),
            )
        } returns
            RunnerSessionBindingResult.Unavailable(
                workspaceId = ws.id,
                runnerStatus = RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS.label,
            )

        val ex =
            assertThrows<AgentRunnerUnavailableException> {
                handler.handle(
                    StageAgentInputCommand(
                        workspaceId = ws.id,
                        sessionId = session.id,
                        content = "payload",
                        name = "notes.txt",
                    ),
                )
            }

        assertThat(ex.runnerStatus).isEqualTo(RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS.label)
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
            podName = null,
            pvcName = null,
            gatewayEndpoint = "http://x:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun session(workspaceId: WorkspaceId) =
        WorkspaceAgentSession(
            id = WorkspaceAgentSessionId.random(),
            workspaceId = workspaceId,
            kind = WorkspaceAgentKind.CLAUDE,
            gatewayAgentId = "abc12345",
            status = WorkspaceAgentSessionStatus.RUNNING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

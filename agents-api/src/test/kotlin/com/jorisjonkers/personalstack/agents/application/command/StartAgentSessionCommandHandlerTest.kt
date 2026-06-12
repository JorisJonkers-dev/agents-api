package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerProvisioningResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.StartRunnerSessionBindingInput
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
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class StartAgentSessionCommandHandlerTest {
    private val binding = mockk<RunnerSessionBindingService>()
    private val handler = StartAgentSessionCommandHandler(binding)

    @Test
    fun `handle starts through runner session binding service`() {
        val command =
            StartAgentSessionCommand(
                sessionId = WorkspaceAgentSessionId.random(),
                workspaceId = WorkspaceId.random(),
                kind = WorkspaceAgentKind.CLAUDE,
            )
        val request = slot<StartRunnerSessionBindingInput>()
        every { binding.start(capture(request)) } returns
            RunnerSessionBindingResult.Bound(
                workspace = workspace(command.workspaceId),
                session = session(command),
                gatewayAgent =
                    AgentGatewayClient.GatewayAgent(
                        id = "abc12345",
                        kind = command.kind,
                        cwd = "/workspace",
                    ),
                provisioning = RunnerProvisioningResult.AlreadyReady,
            )

        handler.handle(command)

        verify(exactly = 1) { binding.start(any()) }
        assertThat(request.captured.workspaceId).isEqualTo(command.workspaceId)
        assertThat(request.captured.sessionId).isEqualTo(command.sessionId)
        assertThat(request.captured.kind).isEqualTo(command.kind)
    }

    private fun workspace(id: WorkspaceId) =
        Workspace(
            id = id,
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

    private fun session(command: StartAgentSessionCommand) =
        WorkspaceAgentSession(
            id = command.sessionId,
            workspaceId = command.workspaceId,
            kind = command.kind,
            gatewayAgentId = "abc12345",
            status = WorkspaceAgentSessionStatus.RUNNING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

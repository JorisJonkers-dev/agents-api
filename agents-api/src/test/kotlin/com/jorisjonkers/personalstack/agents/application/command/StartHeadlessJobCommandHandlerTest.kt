package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.sessionbinding.PrepareRunnerInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerPreparationResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerProvisioningResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class StartHeadlessJobCommandHandlerTest {
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val gateway = mockk<AgentGatewayClient>()
    private val binding = mockk<RunnerSessionBindingService>()
    private val handler = StartHeadlessJobCommandHandler(sessions, gateway, binding)

    @Test
    fun `handle launches headless job and persists session when runner is ready`() {
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        every { binding.prepareRunner(PrepareRunnerInput(ws.id, WorkspaceAgentKind.CLAUDE)) } returns
            RunnerPreparationResult.Ready(
                workspace = ws,
                setupId = AgentSetupId("gpu"),
                setupVersion = AgentSetupVersion(2),
                provisioning = RunnerProvisioningResult.AlreadyReady,
            )
        every {
            gateway.startHeadlessJob(ws, WorkspaceAgentKind.CLAUDE, "write tests", null, null, sessionId, 1, null)
        } returns
            AgentGatewayClient.HeadlessJob(
                id = "hls-abc123",
                status = AgentGatewayClient.HeadlessStatus.RUNNING,
                exitCode = null,
                output = null,
            )
        val saved = slot<WorkspaceAgentSession>()
        every { sessions.save(capture(saved)) } answers { firstArg() }

        handler.handle(
            StartHeadlessJobCommand(
                sessionId = sessionId,
                workspaceId = ws.id,
                kind = WorkspaceAgentKind.CLAUDE,
                prompt = "write tests",
            ),
        )

        assertThat(saved.captured.gatewayAgentId).isEqualTo("hls-abc123")
        assertThat(saved.captured.status).isEqualTo(WorkspaceAgentSessionStatus.RUNNING)
        assertThat(saved.captured.runMode).isEqualTo(StartHeadlessJobCommandHandler.HEADLESS_RUN_MODE)
        assertThat(saved.captured.currentSetupId).isEqualTo(AgentSetupId("gpu"))
        assertThat(saved.captured.currentSetupVersion).isEqualTo(AgentSetupVersion(2))
        verify(exactly = 1) { binding.prepareRunner(any()) }
    }

    @Test
    fun `handle raises NoSuchElementException when workspace does not exist`() {
        val missingId = WorkspaceId.random()
        every { binding.prepareRunner(PrepareRunnerInput(missingId, WorkspaceAgentKind.CLAUDE)) } throws
            NoSuchElementException("workspace not found: ${missingId.value}")

        val ex =
            assertThrows<NoSuchElementException> {
                handler.handle(
                    StartHeadlessJobCommand(
                        sessionId = WorkspaceAgentSessionId.random(),
                        workspaceId = missingId,
                        kind = WorkspaceAgentKind.CLAUDE,
                        prompt = "hello",
                    ),
                )
            }
        assertThat(ex.message).contains(missingId.value.toString())
    }

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
}

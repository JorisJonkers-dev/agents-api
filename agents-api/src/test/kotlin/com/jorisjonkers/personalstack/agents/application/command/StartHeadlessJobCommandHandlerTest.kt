package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
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
    private val runnerLifecycle = mockk<WorkspaceRunnerLifecycleService>()
    private val telemetry = RecordingTelemetry()
    private val handler = StartHeadlessJobCommandHandler(sessions, gateway, runnerLifecycle, telemetry)

    @Test
    fun `handle launches headless job and persists session when runner is ready`() {
        val ws = workspace(WorkspaceId.parse("11111111-1111-4111-8111-111111111111"))
        val sessionId = WorkspaceAgentSessionId.parse("22222222-2222-4222-8222-222222222222")
        val prompt = "write tests for /workspace/private with job hls-secret"
        every {
            runnerLifecycle.boot(ws.id, WorkspaceAgentKind.CLAUDE, AgentSetupId("gpu"), AgentSetupVersion(2))
        } returns
            WorkspaceRunnerLifecycleService.BootOutcome.Ready(
                workspace = ws,
                provisioning = WorkspaceRunnerLifecycleService.BootProvisioningOutcome.AlreadyReady,
            )
        every {
            gateway.startHeadlessJob(ws, WorkspaceAgentKind.CLAUDE, prompt, null, null, sessionId, 1, null)
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
                prompt = prompt,
                setupId = AgentSetupId("gpu"),
                setupVersion = AgentSetupVersion(2),
            ),
        )

        assertThat(saved.captured.gatewayAgentId).isEqualTo("hls-abc123")
        assertThat(saved.captured.status).isEqualTo(WorkspaceAgentSessionStatus.RUNNING)
        assertThat(saved.captured.runMode).isEqualTo(StartHeadlessJobCommandHandler.HEADLESS_RUN_MODE)
        assertThat(saved.captured.currentSetupId).isEqualTo(AgentSetupId("gpu"))
        assertThat(saved.captured.currentSetupVersion).isEqualTo(AgentSetupVersion(2))
        telemetry.operations.single().let { event ->
            assertThat(event.operation).isEqualTo(OperationLabel.START_SESSION)
            assertThat(event.mode).isEqualTo(ModeLabel.HEADLESS)
            assertThat(event.outcome).isEqualTo(OutcomeLabel.SUCCESS)
            assertThat(event.reason).isEqualTo(FailureReasonLabel.NONE)
            assertThat(event.labels()).doesNotContain(
                prompt,
                ws.id.value.toString(),
                ws.gatewayEndpoint.orEmpty(),
                "hls-abc123",
                "/workspace/private",
            )
        }
        verify(exactly = 1) {
            runnerLifecycle.boot(ws.id, WorkspaceAgentKind.CLAUDE, AgentSetupId("gpu"), AgentSetupVersion(2))
        }
    }

    @Test
    fun `handle records bounded unavailable reason when runner is unavailable`() {
        val workspaceId = WorkspaceId.parse("33333333-3333-4333-8333-333333333333")
        every {
            runnerLifecycle.boot(workspaceId, WorkspaceAgentKind.CODEX, null, null)
        } returns WorkspaceRunnerLifecycleService.BootOutcome.Conflict(RunnerUnavailableReason.BOOT_LEASE_HELD)

        val ex =
            assertThrows<AgentRunnerUnavailableException> {
                handler.handle(
                    StartHeadlessJobCommand(
                        sessionId = WorkspaceAgentSessionId.parse("44444444-4444-4444-8444-444444444444"),
                        workspaceId = workspaceId,
                        kind = WorkspaceAgentKind.CODEX,
                        prompt = "do not label this prompt",
                    ),
                )
            }

        assertThat(ex.runnerStatus).isEqualTo(RunnerUnavailableReason.BOOT_LEASE_HELD.label)
        telemetry.operations.single().let { event ->
            assertThat(event.outcome).isEqualTo(OutcomeLabel.FAILURE)
            assertThat(event.reason).isEqualTo(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
            assertThat(event.labels()).doesNotContain(
                workspaceId.value.toString(),
                RunnerUnavailableReason.BOOT_LEASE_HELD.label,
                "do not label this prompt",
            )
        }
    }

    @Test
    fun `handle records bounded launch failure reason without exception details`() {
        val ws = workspace(WorkspaceId.parse("55555555-5555-4555-8555-555555555555"))
        val sessionId = WorkspaceAgentSessionId.parse("66666666-6666-4666-8666-666666666666")
        val exceptionMessage = "gateway refused prompt=secret path=/workspace/private/raw-output.txt"
        every { runnerLifecycle.boot(ws.id, WorkspaceAgentKind.CLAUDE, null, null) } returns
            WorkspaceRunnerLifecycleService.BootOutcome.Ready(
                workspace = ws,
                provisioning = WorkspaceRunnerLifecycleService.BootProvisioningOutcome.AlreadyReady,
            )
        every {
            gateway.startHeadlessJob(ws, WorkspaceAgentKind.CLAUDE, "secret prompt", null, 30, sessionId, 1, null)
        } throws RuntimeException(exceptionMessage)

        assertThrows<AgentRunnerUnavailableException> {
            handler.handle(
                StartHeadlessJobCommand(
                    sessionId = sessionId,
                    workspaceId = ws.id,
                    kind = WorkspaceAgentKind.CLAUDE,
                    prompt = "secret prompt",
                    timeoutSeconds = 30,
                ),
            )
        }

        telemetry.operations.single().let { event ->
            assertThat(event.outcome).isEqualTo(OutcomeLabel.FAILURE)
            assertThat(event.reason).isEqualTo(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
            assertThat(event.labels()).doesNotContain(
                exceptionMessage,
                "secret prompt",
                sessionId.value.toString(),
                "/workspace/private/raw-output.txt",
            )
        }
    }

    @Test
    fun `handle records bounded persistence failure reason after launch succeeds`() {
        val ws = workspace(WorkspaceId.parse("77777777-7777-4777-8777-777777777777"))
        val sessionId = WorkspaceAgentSessionId.parse("88888888-8888-4888-8888-888888888888")
        val exceptionMessage = "cannot persist session $sessionId for output file /workspace/private/result.txt"
        every { runnerLifecycle.boot(ws.id, WorkspaceAgentKind.CLAUDE, null, null) } returns
            WorkspaceRunnerLifecycleService.BootOutcome.Ready(
                workspace = ws,
                provisioning = WorkspaceRunnerLifecycleService.BootProvisioningOutcome.AlreadyReady,
            )
        every {
            gateway.startHeadlessJob(ws, WorkspaceAgentKind.CLAUDE, "persist me", null, null, sessionId, 1, null)
        } returns
            AgentGatewayClient.HeadlessJob(
                id = "hls-persist-raw",
                status = AgentGatewayClient.HeadlessStatus.RUNNING,
                exitCode = null,
                output = null,
            )
        every { sessions.save(any()) } throws RuntimeException(exceptionMessage)

        val ex =
            assertThrows<RuntimeException> {
                handler.handle(
                    StartHeadlessJobCommand(
                        sessionId = sessionId,
                        workspaceId = ws.id,
                        kind = WorkspaceAgentKind.CLAUDE,
                        prompt = "persist me",
                    ),
                )
            }

        assertThat(ex.message).isEqualTo(exceptionMessage)
        telemetry.operations.single().let { event ->
            assertThat(event.outcome).isEqualTo(OutcomeLabel.FAILURE)
            assertThat(event.reason).isEqualTo(FailureReasonLabel.OTHER)
            assertThat(event.labels()).doesNotContain(
                exceptionMessage,
                sessionId.value.toString(),
                "hls-persist-raw",
                "/workspace/private/result.txt",
            )
        }
    }

    @Test
    fun `handle raises unavailable when workspace does not exist`() {
        val missingId = WorkspaceId.parse("99999999-9999-4999-8999-999999999999")
        every {
            runnerLifecycle.boot(missingId, WorkspaceAgentKind.CLAUDE, null, null)
        } returns WorkspaceRunnerLifecycleService.BootOutcome.Conflict(RunnerUnavailableReason.WORKSPACE_NOT_FOUND)

        val ex =
            assertThrows<AgentRunnerUnavailableException> {
                handler.handle(
                    StartHeadlessJobCommand(
                        sessionId = WorkspaceAgentSessionId.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        workspaceId = missingId,
                        kind = WorkspaceAgentKind.CLAUDE,
                        prompt = "hello",
                    ),
                )
            }
        assertThat(ex.runnerStatus).isEqualTo(RunnerUnavailableReason.WORKSPACE_NOT_FOUND.label)
        telemetry.operations.single().let { event ->
            assertThat(event.outcome).isEqualTo(OutcomeLabel.FAILURE)
            assertThat(event.reason).isEqualTo(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
            assertThat(event.labels()).doesNotContain(missingId.value.toString(), ex.message.orEmpty())
        }
    }

    private fun workspace(id: WorkspaceId = WorkspaceId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")) =
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

    private class RecordingTelemetry : AgentsApiTelemetry {
        val operations = mutableListOf<OperationTelemetry>()

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }
    }

    private fun OperationTelemetry.labels(): Set<String> =
        setOf(
            operation.label,
            mode.label,
            outcome.label,
            reason.label,
        )
}

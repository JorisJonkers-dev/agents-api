package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.observability.RunnerReprovisionTelemetry
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RunnerBindingMetricsTest {
    private val recording = RecordingTelemetry()
    private val metrics = RunnerBindingMetrics(recording)
    private val workspaceId = WorkspaceId.random()

    @Test
    fun `observeBinding records success when block returns Bound`() {
        val bound =
            RunnerSessionBindingResult.Bound(
                workspace = stubWorkspace(),
                session = stubSession(),
                gatewayAgent = stubGatewayAgent(),
                provisioning = RunnerProvisioningResult.AlreadyReady,
            )

        val result = metrics.observeBinding(OperationLabel.START_SESSION, ModeLabel.INTERACTIVE) { bound }

        assertThat(result).isSameAs(bound)
        assertThat(recording.operations).hasSize(1)
        recording.operations.single().let {
            assertThat(it.operation).isEqualTo(OperationLabel.START_SESSION)
            assertThat(it.outcome).isEqualTo(OutcomeLabel.SUCCESS)
            assertThat(it.reason).isEqualTo(FailureReasonLabel.NONE)
        }
    }

    @Test
    fun `observeBinding records failure with CAPACITY reason when block returns Conflict`() {
        val conflict = RunnerSessionBindingResult.Conflict(current = null)

        metrics.observeBinding(OperationLabel.ATTACH_SESSION, ModeLabel.INTERACTIVE) { conflict }

        assertThat(recording.operations.single().outcome).isEqualTo(OutcomeLabel.FAILURE)
        assertThat(recording.operations.single().reason).isEqualTo(FailureReasonLabel.CAPACITY)
    }

    @Test
    fun `observeBinding records failure with UPSTREAM_UNAVAILABLE reason when block returns Unavailable`() {
        val unavailable = RunnerSessionBindingResult.Unavailable(workspaceId = workspaceId, runnerStatus = "NotReady")

        metrics.observeBinding(OperationLabel.ATTACH_SESSION, ModeLabel.INTERACTIVE) { unavailable }

        assertThat(recording.operations.single().outcome).isEqualTo(OutcomeLabel.FAILURE)
        assertThat(recording.operations.single().reason).isEqualTo(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
    }

    @Test
    fun `observeBinding records failure and rethrows when block throws`() {
        assertThrows<IllegalStateException> {
            metrics.observeBinding(OperationLabel.START_SESSION, ModeLabel.INTERACTIVE) {
                throw IllegalStateException("boom")
            }
        }
        assertThat(recording.operations.single().outcome).isEqualTo(OutcomeLabel.FAILURE)
        assertThat(recording.operations.single().reason).isEqualTo(FailureReasonLabel.UNKNOWN)
    }

    @Test
    fun `recordReprovision emits both operation and reprovision telemetry`() {
        metrics.recordReprovision(OutcomeLabel.SUCCESS, FailureReasonLabel.NONE, System.nanoTime())

        assertThat(recording.operations).hasSize(1)
        assertThat(recording.reprovisions).hasSize(1)
        assertThat(recording.operations.single().operation).isEqualTo(OperationLabel.REPROVISION_RUNNER)
        assertThat(recording.reprovisions.single().outcome).isEqualTo(OutcomeLabel.SUCCESS)
    }

    @Test
    fun `bindingConflict returns FAILURE and CAPACITY pair`() {
        val (outcome, reason) = metrics.bindingConflict()
        assertThat(outcome).isEqualTo(OutcomeLabel.FAILURE)
        assertThat(reason).isEqualTo(FailureReasonLabel.CAPACITY)
    }

    private fun stubWorkspace() =
        com.jorisjonkers.personalstack.agents.domain.model.Workspace(
            id = workspaceId,
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus.READY,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )

    private fun stubSession() =
        com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession(
            id =
                com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
                    .random(),
            workspaceId = workspaceId,
            kind = com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind.CLAUDE,
            gatewayAgentId = null,
            status = com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus.RUNNING,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )

    private fun stubGatewayAgent() =
        com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient.GatewayAgent(
            id = "agent-1",
            kind = com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind.CLAUDE,
            cwd = "/workspace",
        )

    private class RecordingTelemetry : AgentsApiTelemetry {
        val operations = mutableListOf<OperationTelemetry>()
        val reprovisions = mutableListOf<RunnerReprovisionTelemetry>()

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }

        override fun recordRunnerReprovision(event: RunnerReprovisionTelemetry) {
            reprovisions += event
        }
    }
}

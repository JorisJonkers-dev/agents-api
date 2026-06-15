package com.jorisjonkers.personalstack.agents.application.maintenance

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StaleRunnerSetupLeaseReaperTest {
    private class RecordingTelemetry : AgentsApiTelemetry {
        val operations = mutableListOf<OperationTelemetry>()

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }
    }

    private class FixedClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = now
    }

    private val now = Instant.parse("2026-05-19T12:00:00Z")
    private val clock = FixedClock(now)
    private val leaseTimeoutSeconds = 300L
    private val olderThan = now.minusSeconds(leaseTimeoutSeconds)
    private val workspaces = mockk<WorkspaceRepository>()
    private val telemetry = RecordingTelemetry()
    private val reaper =
        StaleRunnerSetupLeaseReaper(
            workspaces = workspaces,
            clock = clock,
            telemetry = telemetry,
            leaseTimeoutSeconds = leaseTimeoutSeconds,
        )

    @Test
    fun `releases a RESTARTING lease left stale past the timeout`() {
        val ws = workspace(RunnerSetupOperation.RESTARTING, leaseUpdatedAt = now.minusSeconds(600))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        every { workspaces.releaseStaleRunnerSetupOperation(ws.id, olderThan, now) } returns true

        reaper.sweep()

        verify { workspaces.releaseStaleRunnerSetupOperation(ws.id, olderThan, now) }
        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.SUCCESS)
    }

    @Test
    fun `releases a FAILED lease left stale past the timeout`() {
        val ws = workspace(RunnerSetupOperation.FAILED, leaseUpdatedAt = now.minusSeconds(3_600))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        every { workspaces.releaseStaleRunnerSetupOperation(ws.id, olderThan, now) } returns true

        reaper.sweep()

        verify { workspaces.releaseStaleRunnerSetupOperation(ws.id, olderThan, now) }
    }

    @Test
    fun `leaves an IDLE workspace untouched`() {
        val ws = workspace(RunnerSetupOperation.IDLE, leaseUpdatedAt = now.minusSeconds(3_600))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)

        reaper.sweep()

        verify(exactly = 0) { workspaces.releaseStaleRunnerSetupOperation(any(), any(), any()) }
    }

    @Test
    fun `leaves a RESTARTING lease younger than the timeout untouched`() {
        val ws = workspace(RunnerSetupOperation.RESTARTING, leaseUpdatedAt = now.minusSeconds(30))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)

        reaper.sweep()

        verify(exactly = 0) { workspaces.releaseStaleRunnerSetupOperation(any(), any(), any()) }
    }

    @Test
    fun `leaves a non-IDLE workspace with no lease timestamp untouched`() {
        val ws = workspace(RunnerSetupOperation.FAILED, leaseUpdatedAt = null)
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)

        reaper.sweep()

        verify(exactly = 0) { workspaces.releaseStaleRunnerSetupOperation(any(), any(), any()) }
    }

    @Test
    fun `records SKIPPED when the CAS no-ops because the lease moved`() {
        val ws = workspace(RunnerSetupOperation.RESTARTING, leaseUpdatedAt = now.minusSeconds(600))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        every { workspaces.releaseStaleRunnerSetupOperation(ws.id, olderThan, now) } returns false

        reaper.sweep()

        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.SKIPPED)
    }

    @Test
    fun `records a bounded failure when the release throws`() {
        val ws = workspace(RunnerSetupOperation.RESTARTING, leaseUpdatedAt = now.minusSeconds(600))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        every {
            workspaces.releaseStaleRunnerSetupOperation(ws.id, olderThan, now)
        } throws RuntimeException("db down for ${ws.id}")

        reaper.sweep()

        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.FAILURE)
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
        val labels =
            telemetry.operations.flatMap {
                listOf(it.operation.label, it.mode.label, it.outcome.label, it.reason.label)
            }
        assertThat(labels).allSatisfy { assertThat(it).doesNotContain(ws.id.value.toString()) }
    }

    private fun workspace(
        operation: RunnerSetupOperation,
        leaseUpdatedAt: Instant?,
    ) = Workspace(
        id = WorkspaceId.random(),
        name = "demo",
        repoUrl = "git@github.com:o/r.git",
        branch = "main",
        podName = "agent-runner-deadbeef",
        pvcName = "workspace-deadbeef",
        gatewayEndpoint = "http://x:8090",
        status = WorkspaceStatus.READY,
        createdAt = now.minusSeconds(86_400),
        updatedAt = now.minusSeconds(86_400),
        runnerSetupOperation = operation,
        runnerSetupOperationStartedAt = leaseUpdatedAt,
        runnerSetupOperationUpdatedAt = leaseUpdatedAt,
    )
}

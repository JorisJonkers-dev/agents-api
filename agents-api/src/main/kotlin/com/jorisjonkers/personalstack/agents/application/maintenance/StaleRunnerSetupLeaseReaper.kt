package com.jorisjonkers.personalstack.agents.application.maintenance

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Periodic sweep that releases runner-setup leases stuck in a non-IDLE
 * state. A session `start`/`restart` claims the workspace's
 * `runnerSetupOperation` (IDLE -> RESTARTING) for the duration of
 * provisioning, then either completes it back to IDLE or fails it to
 * FAILED. Both RESTARTING and FAILED block every subsequent
 * `start`/`restart` (`prepareRunner` requires IDLE) and exempt the
 * workspace from idle scale-down. Nothing else ever resets them, so two
 * cases would otherwise wedge a workspace permanently:
 *
 *  - **Crash mid-provision** — agents-api dies (e.g. a rollout) between
 *    claiming the lease and completing/failing it, leaving RESTARTING
 *    with no thread to release it.
 *  - **Failed provision** — a provision that fails leaves FAILED, which
 *    no code path clears.
 *
 * Provisioning holds the lease for only a few seconds (a bounded,
 * non-blocking readiness gate), so the [leaseTimeoutSeconds] threshold —
 * minutes by default — never preempts a live operation; it only reclaims
 * leases whose owning operation is gone. The reclaimed workspace reverts
 * to its last good setup (pending dropped) and can bind sessions again.
 */
@Component
class StaleRunnerSetupLeaseReaper(
    private val workspaces: WorkspaceRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
    @param:Value("\${agent-runtime.runner-setup-lease-timeout-seconds:300}")
    private val leaseTimeoutSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(StaleRunnerSetupLeaseReaper::class.java)

    @Scheduled(fixedDelayString = "\${agent-runtime.runner-setup-lease-sweep-period-ms:120000}")
    fun sweep() {
        val now = clock.instant()
        val olderThan = now.minus(Duration.ofSeconds(leaseTimeoutSeconds))
        val stale =
            workspaces
                .findAllByStatusNot(WorkspaceStatus.DESTROYED)
                .filter { it.isStaleLease(olderThan) }
        if (stale.isEmpty()) return

        val released = stale.count { reclaim(it, olderThan, now) }
        if (released > 0) log.info("runner-setup-lease reap released {} workspace(s)", released)
    }

    private fun reclaim(
        workspace: Workspace,
        olderThan: Instant,
        now: Instant,
    ): Boolean {
        val reclaimed =
            runCatching { workspaces.releaseStaleRunnerSetupOperation(workspace.id, olderThan, now) }
                .getOrElse {
                    log.warn("runner-setup-lease reap of {} failed: {}", workspace.id, it.message)
                    record(OutcomeLabel.FAILURE, FailureReasonLabel.UPSTREAM_UNAVAILABLE)
                    return false
                }
        if (reclaimed) {
            record(OutcomeLabel.SUCCESS, FailureReasonLabel.NONE)
            log.warn(
                "released stale runner-setup lease for workspace {} (was {} since {})",
                workspace.id.value,
                workspace.runnerSetupOperation,
                workspace.runnerSetupOperationUpdatedAt,
            )
        } else {
            record(OutcomeLabel.SKIPPED, FailureReasonLabel.NONE)
        }
        return reclaimed
    }

    private fun Workspace.isStaleLease(olderThan: Instant): Boolean {
        if (runnerSetupOperation == RunnerSetupOperation.IDLE) return false
        val updatedAt = runnerSetupOperationUpdatedAt ?: return false
        return updatedAt.isBefore(olderThan)
    }

    private fun record(
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
    ) {
        telemetry.recordOperation(
            OperationTelemetry(
                operation = OperationLabel.REPROVISION_RUNNER,
                mode = ModeLabel.OTHER,
                outcome = outcome,
                reason = reason,
                duration = Duration.ZERO,
            ),
        )
    }
}

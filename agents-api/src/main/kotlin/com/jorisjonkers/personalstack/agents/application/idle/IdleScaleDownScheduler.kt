package com.jorisjonkers.personalstack.agents.application.idle

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Periodic sweep: any READY workspace that is both idle long enough
 * and has no connected browser clients gets its Pod + Service torn
 * down via [AgentRunnerOrchestrator.scaleDown].
 *
 * Scale-down is suppressed when:
 *  - A browser WebSocket is open ([ConnectedClientTracker.isConnected]).
 *  - The workspace has RUNNING agent sessions and their last activity
 *    is within `agent-runtime.agent-idle-after-seconds` (default 4 h).
 *    This protects long-running autonomous agent tasks after the user
 *    disconnects — the idle timer resets while the AI streams output.
 *    Once the AI goes quiet the longer grace period begins.
 *
 * Defaults: 30 min for sessions-idle, 4 h for agent-running, sweep
 * every 5 min. All overridable via env.
 */
@Component
class IdleScaleDownScheduler(
    private val workspaces: WorkspaceRepository,
    private val agentSessions: WorkspaceAgentSessionRepository,
    private val orchestrator: AgentRunnerOrchestrator,
    private val gateway: AgentGatewayClient,
    private val tracker: WorkspaceActivityTracker,
    private val connected: ConnectedClientTracker,
    private val sessionStatus: SessionStatusPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
    @param:Value("\${agent-runtime.idle-after-seconds:1800}")
    private val idleAfterSeconds: Long,
    @param:Value("\${agent-runtime.agent-idle-after-seconds:14400}")
    private val agentIdleAfterSeconds: Long,
    @param:Value("\${agent-runtime.stale-recycle-quiet-seconds:300}")
    private val staleRecycleQuietSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(IdleScaleDownScheduler::class.java)

    @Scheduled(fixedDelayString = "\${agent-runtime.idle-sweep-period-ms:300000}")
    fun sweep() {
        val candidates =
            workspaces
                .findAllByStatusNot(WorkspaceStatus.DESTROYED)
                .filter { workspace ->
                    if (workspace.status != WorkspaceStatus.READY) {
                        recordScaleDown(OutcomeLabel.SKIPPED, FailureReasonLabel.INVALID_REQUEST)
                        false
                    } else {
                        val eligible = isEligibleForScaleDown(workspace)
                        if (!eligible) recordScaleDown(OutcomeLabel.SKIPPED, FailureReasonLabel.NONE)
                        eligible
                    }
                }
        candidates.forEach { scaleDown(it) }
        if (candidates.isNotEmpty()) log.info("idle-sweep scaled down {} workspace(s)", candidates.size)
    }

    @Suppress("ReturnCount")
    private fun isEligibleForScaleDown(workspace: Workspace): Boolean {
        if (workspace.hasRunnerSetupGuard()) return false
        if (connected.isConnected(workspace.id)) return false
        val sessions = agentSessions.findAllByWorkspaceId(workspace.id)
        if (sessions.any { it.pendingSetupId != null || it.pendingSetupVersion != null }) return false
        // No client is attached (checked above). If the runner is on an image
        // from an older release, recycle it so the next attach comes back on
        // the current image — but only once every running agent has gone quiet,
        // so an agent that is still working is never interrupted mid-task.
        if (orchestrator.isRunnerImageStale(workspace)) return staleRunnerSafeToRecycle(workspace, sessions)
        val lastSeen = effectiveLastSeen(workspace)
        val hasRunning = sessions.any { it.status == WorkspaceAgentSessionStatus.RUNNING }
        val threshold =
            Duration.ofSeconds(if (hasRunning) agentIdleAfterSeconds else idleAfterSeconds)
        return !lastSeen.isAfter(clock.instant().minus(threshold))
    }

    /**
     * A stale runner may only be recycled once every running agent has been
     * idle (no output) for the grace window. A session with no live agent is
     * nothing to interrupt. If any agent is still producing output — or its
     * idle time can't be confirmed from the gateway — we wait for the next
     * sweep rather than risk cutting off an in-progress task.
     */
    private fun staleRunnerSafeToRecycle(
        workspace: Workspace,
        sessions: List<WorkspaceAgentSession>,
    ): Boolean {
        val running = sessions.filter { it.status == WorkspaceAgentSessionStatus.RUNNING }
        if (running.isEmpty()) return true
        val grace = Duration.ofSeconds(staleRecycleQuietSeconds)
        return running.all { session ->
            val agentId = session.gatewayAgentId ?: return@all false
            val idle = gateway.agentIdle(workspace, agentId)
            idle != null && idle >= grace
        }
    }

    private fun effectiveLastSeen(workspace: Workspace): Instant = tracker.lastSeen(workspace.id) ?: workspace.updatedAt

    private fun scaleDown(workspace: Workspace) {
        runCatching {
            orchestrator.scaleDown(workspace)
            workspaces.save(
                workspace.copy(
                    status = WorkspaceStatus.IDLE,
                    podName = null,
                    gatewayEndpoint = null,
                    updatedAt = clock.instant(),
                ),
            )
            clearGatewayBindings(workspace)
            tracker.forget(workspace.id)
        }.onFailure {
            log.warn("scale-down of {} failed: {}", workspace.id, it.message)
            recordScaleDown(OutcomeLabel.FAILURE, FailureReasonLabel.UPSTREAM_UNAVAILABLE)
            return
        }
        recordScaleDown(OutcomeLabel.SUCCESS, FailureReasonLabel.NONE)
        log.info("workspace {} idle-scaled to zero (last seen {})", workspace.id, effectiveLastSeen(workspace))
    }

    private fun clearGatewayBindings(workspace: Workspace) {
        val now = clock.instant()
        agentSessions
            .findAllByWorkspaceId(workspace.id)
            .filter { it.gatewayAgentId != null || it.gatewayBoundAt != null }
            .forEach {
                val cleared =
                    agentSessions.clearGatewayBindingIfGeneration(
                        id = it.id,
                        expectedGeneration = it.generation,
                        now = now,
                    )
                if (cleared) sessionStatus.publishStatus(it.clearGatewayBinding(now), idle = true)
            }
    }

    private fun Workspace.hasRunnerSetupGuard(): Boolean =
        runnerSetupOperation != RunnerSetupOperation.IDLE ||
            pendingRunnerSetupId != null ||
            pendingRunnerSetupVersion != null ||
            runnerBootLeaseId != null

    private fun recordScaleDown(
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

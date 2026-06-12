package com.jorisjonkers.personalstack.agents.application.idle

import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
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
    private val tracker: WorkspaceActivityTracker,
    private val connected: ConnectedClientTracker,
    private val sessionStatus: SessionStatusPublisher,
    private val clock: Clock = Clock.systemUTC(),
    @param:Value("\${agent-runtime.idle-after-seconds:1800}")
    private val idleAfterSeconds: Long,
    @param:Value("\${agent-runtime.agent-idle-after-seconds:14400}")
    private val agentIdleAfterSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(IdleScaleDownScheduler::class.java)

    @Scheduled(fixedDelayString = "\${agent-runtime.idle-sweep-period-ms:300000}")
    fun sweep() {
        val candidates =
            workspaces
                .findAllByStatusNot(WorkspaceStatus.DESTROYED)
                .filter { it.status == WorkspaceStatus.READY && isEligibleForScaleDown(it) }
        candidates.forEach { scaleDown(it) }
        if (candidates.isNotEmpty()) log.info("idle-sweep scaled down {} workspace(s)", candidates.size)
    }

    @Suppress("ReturnCount")
    private fun isEligibleForScaleDown(workspace: Workspace): Boolean {
        if (workspace.hasRunnerSetupGuard()) return false
        if (connected.isConnected(workspace.id)) return false
        val lastSeen = effectiveLastSeen(workspace)
        val sessions = agentSessions.findAllByWorkspaceId(workspace.id)
        if (sessions.any { it.pendingSetupId != null || it.pendingSetupVersion != null }) return false
        val hasRunning = sessions.any { it.status == WorkspaceAgentSessionStatus.RUNNING }
        val threshold =
            Duration.ofSeconds(if (hasRunning) agentIdleAfterSeconds else idleAfterSeconds)
        return !lastSeen.isAfter(clock.instant().minus(threshold))
    }

    private fun effectiveLastSeen(workspace: Workspace): Instant = tracker.lastSeen(workspace.id) ?: workspace.updatedAt

    private fun scaleDown(workspace: Workspace) {
        runCatching { orchestrator.scaleDown(workspace) }
            .onFailure {
                log.warn("scale-down of {} failed: {}", workspace.id, it.message)
                return
            }
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
            pendingRunnerSetupVersion != null
}

package com.jorisjonkers.personalstack.agents.application.maintenance

import com.jorisjonkers.personalstack.agents.application.idle.WorkspaceActivityTracker
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Bulk maintenance operations on runner Pods. Unlike [IdleScaleDownScheduler],
 * which acts on a time threshold, these operations are admin-triggered
 * and act on all active workspaces immediately.
 *
 * Primary use cases:
 *  - **Rolling restart**: after pushing a new agent-runner image, call
 *    [gracefulScaleDownAll] to tear down every Pod. The next session
 *    start on each workspace will re-provision via [AgentRunnerOrchestrator.provision],
 *    pulling `:latest` automatically. The workspace PVC is preserved,
 *    but a later "new session" starts a fresh agent process instead
 *    of resuming a prior native CLI conversation.
 *  - **Pre-node-drain**: before draining the runner node, call
 *    [gracefulScaleDownAll] so Pods are stopped cleanly rather than
 *    evicted. After the node returns, workspaces re-provision on demand.
 */
@Component
class RunnerMaintenanceService(
    private val workspaces: WorkspaceRepository,
    private val agentSessions: WorkspaceAgentSessionRepository,
    private val orchestrator: AgentRunnerOrchestrator,
    private val tracker: WorkspaceActivityTracker,
    private val sessionStatus: SessionStatusPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(RunnerMaintenanceService::class.java)

    data class MaintenanceResult(
        val cycled: Int,
        val workspaceIds: List<String>,
    )

    private val activeStatuses = setOf(WorkspaceStatus.READY, WorkspaceStatus.STARTING, WorkspaceStatus.FAILED)

    /**
     * Scale down every workspace whose Pod is (or should be) running.
     * Each workspace transitions to [WorkspaceStatus.IDLE] with its PVC
     * preserved so the next [AgentRunnerOrchestrator.provision] re-attaches
     * the same disk and CLI history.
     *
     * Scale-down failures are logged and skipped — the orchestrator's
     * [AgentRunnerOrchestrator.scaleDown] treats a missing Pod as a no-op,
     * so the only real failure is an unreachable k8s API server.
     */
    fun gracefulScaleDownAll(): MaintenanceResult {
        val candidates =
            workspaces
                .findAllByStatusNot(WorkspaceStatus.DESTROYED)
                .filter { it.status in activeStatuses && !it.hasRunnerSetupGuard() && !hasPendingSetupSession(it) }
        val cycled = candidates.mapNotNull { scaleDownToIdle(it) }
        if (cycled.isNotEmpty()) {
            log.info("maintenance: graceful scale-down cycled {} workspace(s)", cycled.size)
        }
        return MaintenanceResult(
            cycled = cycled.size,
            workspaceIds = cycled,
        )
    }

    /** Returns the workspace id string on success, null when scaleDown failed. */
    private fun scaleDownToIdle(workspace: Workspace): String? {
        runCatching { orchestrator.scaleDown(workspace) }
            .onFailure {
                log.warn("maintenance: scale-down of {} failed: {} — skipping", workspace.id, it.message)
                return null
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
        log.info("maintenance: workspace {} scaled to zero (was {})", workspace.id, workspace.status)
        return workspace.id.value.toString()
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

    private fun hasPendingSetupSession(workspace: Workspace): Boolean =
        agentSessions
            .findAllByWorkspaceId(workspace.id)
            .any { it.pendingSetupId != null || it.pendingSetupVersion != null }

    private fun Workspace.hasRunnerSetupGuard(): Boolean =
        runnerSetupOperation != RunnerSetupOperation.IDLE ||
            pendingRunnerSetupId != null ||
            pendingRunnerSetupVersion != null
}

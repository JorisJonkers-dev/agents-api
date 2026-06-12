package com.jorisjonkers.personalstack.agents.application.retention

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class DurableSessionCleanupService(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val gateway: AgentGatewayClient,
    private val orchestrator: AgentRunnerOrchestrator,
    private val runtime: AgentRuntimeProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(DurableSessionCleanupService::class.java)

    data class CleanupResult(
        val markedPending: Int,
        val cleaned: Int,
        val failed: Int,
    )

    @Scheduled(fixedDelayString = "\${agent-runtime.durable-session-cleanup-sweep-period-ms:300000}")
    fun scheduledSweep() {
        sweep()
    }

    fun sweep(): CleanupResult {
        val markedPending = markExpiredSessionsPending()
        val pending = sessions.findCleanupRequested(runtime.durableSessionCleanupBatchSize)
        var cleaned = 0
        var failed = 0
        pending.forEach { session ->
            if (cleanupPendingSession(session)) {
                cleaned += 1
            } else {
                failed += 1
            }
        }
        if (markedPending > 0 || cleaned > 0 || failed > 0) {
            log.info(
                "durable-session-cleanup markedPending={} cleaned={} failed={}",
                markedPending,
                cleaned,
                failed,
            )
        }
        return CleanupResult(
            markedPending = markedPending,
            cleaned = cleaned,
            failed = failed,
        )
    }

    private fun markExpiredSessionsPending(): Int {
        val now = clock.instant()
        return sessions
            .findReadyForCleanup(now, runtime.durableSessionCleanupBatchSize)
            .count { sessions.markCleanupRequested(it.id, now) }
    }

    private fun cleanupPendingSession(session: WorkspaceAgentSession): Boolean {
        val workspace =
            workspaces.findById(session.workspaceId)
                ?: run {
                    log.warn("cleanup pending session {} has missing workspace {}", session.id, session.workspaceId)
                    return false
                }
        val mountedWorkspace = ensureRunnerMounted(workspace) ?: return false
        return runCatching {
            gateway.cleanupStableSession(mountedWorkspace, session.id)
            sessions.delete(session.id)
        }.onFailure {
            log.warn("durable cleanup of session {} failed: {}", session.id, it.message)
        }.isSuccess
    }

    private fun ensureRunnerMounted(workspace: Workspace): Workspace? {
        if (runCatching { gateway.isReady(workspace) }.getOrDefault(false)) return workspace
        val handle =
            runCatching {
                orchestrator.scaleDown(workspace)
                orchestrator.provision(workspace)
            }.onFailure {
                log.warn("durable cleanup could not provision runner for {}: {}", workspace.id, it.message)
            }.getOrNull() ?: return null
        val saved =
            workspaces.save(
                workspace.copy(
                    podName = handle.podName,
                    pvcName = handle.pvcName,
                    gatewayEndpoint = handle.gatewayEndpoint,
                    status = WorkspaceStatus.STARTING,
                    updatedAt = clock.instant(),
                ),
            )
        return if (runCatching { gateway.isReady(saved) }.getOrDefault(false)) {
            saved
        } else {
            log.warn("durable cleanup runner for {} is not ready after provisioning", workspace.id)
            null
        }
    }
}

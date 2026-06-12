package com.jorisjonkers.personalstack.agents.application.retention

import com.jorisjonkers.personalstack.agents.application.sessionbinding.PrepareRunnerInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerPreparationResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
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
    private val binding: RunnerSessionBindingService,
    private val runtime: AgentRuntimeProperties,
    private val sessionStatus: SessionStatusPublisher,
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

    @Suppress("ReturnCount")
    private fun cleanupPendingSession(session: WorkspaceAgentSession): Boolean {
        if (session.pendingSetupId != null || session.pendingSetupVersion != null) return false
        val workspace =
            workspaces.findById(session.workspaceId)
                ?: run {
                    log.warn("cleanup pending session {} has missing workspace {}", session.id, session.workspaceId)
                    return false
                }
        val mountedWorkspace = ensureRunnerMounted(workspace, session) ?: return false
        return runCatching {
            gateway.cleanupStableSession(mountedWorkspace, session.id)
            sessions.delete(session.id).also { deleted ->
                if (deleted) {
                    runCatching { sessionStatus.publishRemove(session.id) }
                        .onFailure {
                            log.warn(
                                "session-status remove publish for {} failed: {}",
                                session.id,
                                it.message,
                            )
                        }
                }
            }
        }.onFailure {
            log.warn("durable cleanup of session {} failed: {}", session.id, it.message)
        }.getOrDefault(false)
    }

    private fun ensureRunnerMounted(
        workspace: Workspace,
        session: WorkspaceAgentSession,
    ): Workspace? {
        if (workspace.hasRunnerSetupGuard()) return null
        if (runCatching { gateway.isReady(workspace) }.getOrDefault(false)) return workspace
        return when (
            val prepared =
                binding.prepareRunner(
                    PrepareRunnerInput(
                        workspaceId = workspace.id,
                        kind = session.kind,
                    ),
                )
        ) {
            is RunnerPreparationResult.Ready -> prepared.workspace
            is RunnerPreparationResult.Unavailable -> {
                log.warn("durable cleanup could not provision runner for {}: {}", workspace.id, prepared.runnerStatus)
                null
            }
        }
    }

    private fun Workspace.hasRunnerSetupGuard(): Boolean =
        runnerSetupOperation != RunnerSetupOperation.IDLE ||
            pendingRunnerSetupId != null ||
            pendingRunnerSetupVersion != null
}

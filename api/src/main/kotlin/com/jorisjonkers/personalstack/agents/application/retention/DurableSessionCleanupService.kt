@file:Suppress("LongMethod")

package com.jorisjonkers.personalstack.agents.application.retention

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
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
import java.time.Duration

@Component
class DurableSessionCleanupService(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val gateway: AgentGatewayClient,
    private val runnerLifecycle: WorkspaceRunnerLifecycleService,
    private val runtime: AgentRuntimeProperties,
    private val sessionStatus: SessionStatusPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
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
        if (session.cleanupRequestedAt == null) {
            recordCleanup(OutcomeLabel.SKIPPED, FailureReasonLabel.INVALID_REQUEST)
            return false
        }
        if (session.pendingSetupId != null || session.pendingSetupVersion != null) {
            recordCleanup(OutcomeLabel.SKIPPED, FailureReasonLabel.CANCELLED)
            return false
        }
        val workspace =
            workspaces.findById(session.workspaceId)
                ?: run {
                    log.warn("cleanup pending session {} has missing workspace {}", session.id, session.workspaceId)
                    recordCleanup(OutcomeLabel.SKIPPED, FailureReasonLabel.NOT_FOUND)
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
                } else {
                    recordCleanup(OutcomeLabel.SKIPPED, FailureReasonLabel.NOT_FOUND)
                }
            }
        }.onFailure {
            log.warn("durable cleanup of session {} failed: {}", session.id, it.message)
            recordCleanup(OutcomeLabel.FAILURE, FailureReasonLabel.IO_ERROR)
        }.onSuccess { deleted ->
            if (deleted) recordCleanup(OutcomeLabel.SUCCESS, FailureReasonLabel.NONE)
        }.getOrDefault(false)
    }

    @Suppress("ReturnCount")
    private fun ensureRunnerMounted(
        workspace: Workspace,
        session: WorkspaceAgentSession,
    ): Workspace? {
        if (workspace.hasRunnerSetupGuard()) {
            recordCleanup(OutcomeLabel.SKIPPED, FailureReasonLabel.CANCELLED)
            return null
        }
        if (runCatching { gateway.isReady(workspace) }.getOrDefault(false)) return workspace
        val bootOutcome =
            runCatching {
                runnerLifecycle.boot(workspaceId = workspace.id, kind = session.kind)
            }.getOrElse { ex ->
                log.warn("durable cleanup runner boot failed for {}: {}", workspace.id, ex.message)
                recordCleanup(OutcomeLabel.FAILURE, FailureReasonLabel.UPSTREAM_UNAVAILABLE)
                return null
            }
        return when (bootOutcome) {
            is WorkspaceRunnerLifecycleService.BootOutcome.Ready -> bootOutcome.workspace
            is WorkspaceRunnerLifecycleService.BootOutcome.Conflict -> {
                log.warn(
                    "durable cleanup could not provision runner for {}: {}",
                    workspace.id,
                    bootOutcome.reason.label,
                )
                recordCleanup(OutcomeLabel.FAILURE, FailureReasonLabel.UPSTREAM_UNAVAILABLE)
                null
            }
        }
    }

    private fun Workspace.hasRunnerSetupGuard(): Boolean =
        runnerSetupOperation != RunnerSetupOperation.IDLE ||
            pendingRunnerSetupId != null ||
            pendingRunnerSetupVersion != null

    private fun recordCleanup(
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
    ) {
        telemetry.recordOperation(
            OperationTelemetry(
                operation = OperationLabel.OTHER,
                mode = ModeLabel.DURABLE,
                outcome = outcome,
                reason = reason,
                duration = Duration.ZERO,
            ),
        )
    }
}

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
class DurableSessionCleanupDependencies(
    val workspaces: WorkspaceRepository,
    val sessions: WorkspaceAgentSessionRepository,
    val gateway: AgentGatewayClient,
    val runnerLifecycle: WorkspaceRunnerLifecycleService,
    val sessionStatus: SessionStatusPublisher,
    val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
)

@Component
class DurableSessionCleanupService(
    dependencies: DurableSessionCleanupDependencies,
    private val runtime: AgentRuntimeProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val workspaces = dependencies.workspaces
    private val sessions = dependencies.sessions
    private val gateway = dependencies.gateway
    private val runnerLifecycle = dependencies.runnerLifecycle
    private val sessionStatus = dependencies.sessionStatus
    private val telemetry = dependencies.telemetry
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
        cleanupSkipReason(session)?.let { reason ->
            recordCleanup(OutcomeLabel.SKIPPED, reason)
            return false
        }
        val mountedWorkspace = cleanupWorkspace(session)?.let { ensureRunnerMounted(it, session) }
        if (mountedWorkspace == null) return false
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

    private fun cleanupSkipReason(session: WorkspaceAgentSession): FailureReasonLabel? =
        when {
            session.cleanupRequestedAt == null -> FailureReasonLabel.INVALID_REQUEST
            session.pendingSetupId != null || session.pendingSetupVersion != null -> FailureReasonLabel.CANCELLED
            else -> null
        }

    private fun cleanupWorkspace(session: WorkspaceAgentSession): Workspace? {
        val workspace = workspaces.findById(session.workspaceId)
        if (workspace == null) {
            log.warn("cleanup pending session {} has missing workspace {}", session.id, session.workspaceId)
            recordCleanup(OutcomeLabel.SKIPPED, FailureReasonLabel.NOT_FOUND)
        }
        return workspace
    }

    private fun ensureRunnerMounted(
        workspace: Workspace,
        session: WorkspaceAgentSession,
    ): Workspace? {
        if (workspace.hasRunnerSetupGuard()) recordCleanup(OutcomeLabel.SKIPPED, FailureReasonLabel.CANCELLED)
        return when {
            workspace.hasRunnerSetupGuard() -> null
            runCatching { gateway.isReady(workspace) }.getOrDefault(false) -> workspace
            else -> bootRunnerForCleanup(workspace, session)
        }
    }

    private fun bootRunnerForCleanup(
        workspace: Workspace,
        session: WorkspaceAgentSession,
    ): Workspace? {
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

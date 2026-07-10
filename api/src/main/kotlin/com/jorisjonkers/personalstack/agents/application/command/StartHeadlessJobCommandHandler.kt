package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Boots the runner if needed via the workspace lifecycle API, submits a
 * one-shot headless job to the gateway, and persists a
 * [WorkspaceAgentSession] to track it.
 *
 * Two-phase commit: the session is first saved as STARTING with no
 * gatewayAgentId (phase 1, committed independently). After the gateway
 * call succeeds the session is updated to RUNNING with the returned job
 * id (phase 2). If the process crashes between the two phases the session
 * remains STARTING and can be reconciled on the next startup sweep.
 * If the gateway call itself fails the session is updated to FAILED so the
 * STARTING row does not linger.
 *
 * Idle sweep protection (skipping workspaces with running headless jobs)
 * depends on N3's `run_mode` column — activate after N3 merges.
 */
@Component
class StartHeadlessJobCommandHandler(
    private val sessions: WorkspaceAgentSessionRepository,
    private val gateway: AgentGatewayClient,
    private val runnerLifecycle: WorkspaceRunnerLifecycleService,
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
) : CommandHandler<StartHeadlessJobCommand> {
    private val log = LoggerFactory.getLogger(StartHeadlessJobCommandHandler::class.java)

    override fun handle(command: StartHeadlessJobCommand) {
        val startedAt = Instant.now()
        runCatching {
            val runner = bootRunner(command)
            val pendingSession = saveStartingSession(command, runner)
            val job = launchJob(runner, command, pendingSession)
            persistSession(pendingSession, job)
            log.info("headless job {} launched for workspace {}", job.id, runner.workspace.id.value)
        }.onSuccess {
            recordCommandOperation(startedAt, OutcomeLabel.SUCCESS, FailureReasonLabel.NONE)
        }.onFailure { ex ->
            recordCommandOperation(startedAt, OutcomeLabel.FAILURE, commandFailureReason(ex))
        }.getOrThrow()
    }

    private fun bootRunner(command: StartHeadlessJobCommand): WorkspaceRunnerLifecycleService.BootOutcome.Ready {
        val result =
            runnerLifecycle.boot(
                workspaceId = command.workspaceId,
                kind = command.kind,
                setupId = command.setupId,
                setupVersion = command.setupVersion,
            )
        return when (result) {
            is WorkspaceRunnerLifecycleService.BootOutcome.Ready -> result
            is WorkspaceRunnerLifecycleService.BootOutcome.Conflict ->
                throw AgentRunnerUnavailableException(
                    workspaceId = command.workspaceId,
                    runnerStatus = result.reason.label,
                    retryAfterSeconds = AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS,
                )
        }
    }

    /**
     * Phase 1: persist a STARTING placeholder before the gateway call.
     * Committed in its own transaction so the row exists on disk even if
     * the process crashes during the gateway round-trip.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun saveStartingSession(
        command: StartHeadlessJobCommand,
        runner: WorkspaceRunnerLifecycleService.BootOutcome.Ready,
    ): WorkspaceAgentSession {
        val now = Instant.now()
        val session =
            WorkspaceAgentSession(
                id = command.sessionId,
                workspaceId = runner.workspace.id,
                kind = command.kind,
                gatewayAgentId = null,
                status = WorkspaceAgentSessionStatus.STARTING,
                createdAt = now,
                updatedAt = now,
                runMode = HEADLESS_RUN_MODE,
                currentSetupId = command.setupId ?: runner.workspace.currentRunnerSetupId,
                currentSetupVersion = command.setupVersion ?: runner.workspace.currentRunnerSetupVersion,
                epoch = 1,
                generation = 1,
            )
        return sessions.save(session)
    }

    private fun launchJob(
        runner: WorkspaceRunnerLifecycleService.BootOutcome.Ready,
        command: StartHeadlessJobCommand,
        pendingSession: WorkspaceAgentSession,
    ): AgentGatewayClient.HeadlessJob =
        runCatching {
            gateway.startHeadlessJob(
                AgentGatewayClient.HeadlessJobRequest(
                    workspace = runner.workspace,
                    kind = command.kind,
                    prompt = command.prompt,
                    timeoutSeconds = command.timeoutSeconds,
                    stableSessionId = command.sessionId,
                    epoch = 1,
                ),
            )
        }.getOrElse { ex ->
            // Gateway call failed — mark the already-saved STARTING session as FAILED
            // so it doesn't linger as an irreconcilable STARTING row.
            markSessionFailed(pendingSession)
            throw AgentRunnerUnavailableException(
                workspaceId = runner.workspace.id,
                runnerStatus = "HeadlessLaunchFailed",
                cause = ex,
            )
        }

    /**
     * Phase 2: update the STARTING session to RUNNING now that the gateway
     * has accepted the job and returned its id.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun persistSession(
        pendingSession: WorkspaceAgentSession,
        job: AgentGatewayClient.HeadlessJob,
    ) {
        sessions.save(
            pendingSession.copy(
                gatewayAgentId = job.id,
                status = WorkspaceAgentSessionStatus.RUNNING,
                updatedAt = Instant.now(),
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun markSessionFailed(session: WorkspaceAgentSession) {
        sessions.save(session.markFailed(now = Instant.now()))
    }

    companion object {
        const val HEADLESS_RUN_MODE = "HEADLESS"
    }

    private fun recordCommandOperation(
        startedAt: Instant,
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
    ) {
        telemetry.recordOperation(
            OperationTelemetry(
                operation = OperationLabel.START_SESSION,
                mode = ModeLabel.HEADLESS,
                outcome = outcome,
                reason = reason,
                duration = Duration.between(startedAt, Instant.now()),
            ),
        )
    }

    private fun commandFailureReason(ex: Throwable): FailureReasonLabel =
        when (ex) {
            is AgentRunnerUnavailableException -> FailureReasonLabel.UPSTREAM_UNAVAILABLE
            is NoSuchElementException -> FailureReasonLabel.NOT_FOUND
            else -> FailureReasonLabel.fromRaw(ex.message)
        }
}

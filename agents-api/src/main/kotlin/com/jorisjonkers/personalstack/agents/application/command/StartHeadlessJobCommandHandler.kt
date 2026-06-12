package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.sessionbinding.PrepareRunnerInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerPreparationResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Provisions the runner if needed, submits a one-shot headless job to
 * the gateway, and persists a [WorkspaceAgentSession] to track it.
 *
 * Idle sweep protection (skipping workspaces with running headless jobs)
 * depends on N3's `run_mode` column — activate after N3 merges.
 */
@Component
class StartHeadlessJobCommandHandler(
    private val sessions: WorkspaceAgentSessionRepository,
    private val gateway: AgentGatewayClient,
    private val binding: RunnerSessionBindingService,
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
) : CommandHandler<StartHeadlessJobCommand> {
    private val log = LoggerFactory.getLogger(StartHeadlessJobCommandHandler::class.java)

    @Transactional
    override fun handle(command: StartHeadlessJobCommand) {
        val startedAt = Instant.now()
        runCatching {
            val runner = prepareRunner(command)
            val job = launchJob(runner, command)
            persistSession(command, runner, job)
            log.info("headless job {} launched for workspace {}", job.id, runner.workspace.id.value)
        }.onSuccess {
            recordCommandOperation(startedAt, OutcomeLabel.SUCCESS, FailureReasonLabel.NONE)
        }.onFailure { ex ->
            recordCommandOperation(startedAt, OutcomeLabel.FAILURE, commandFailureReason(ex))
        }.getOrThrow()
    }

    private fun prepareRunner(command: StartHeadlessJobCommand): RunnerPreparationResult.Ready {
        val result =
            binding.prepareRunner(
                PrepareRunnerInput(
                    workspaceId = command.workspaceId,
                    kind = command.kind,
                    setupId = command.setupId,
                    setupVersion = command.setupVersion,
                ),
            )
        return when (result) {
            is RunnerPreparationResult.Ready -> result
            is RunnerPreparationResult.Unavailable ->
                throw AgentRunnerUnavailableException(
                    workspaceId = result.workspaceId,
                    runnerStatus = result.runnerStatus,
                    retryAfterSeconds =
                        result.retryAfterSeconds ?: AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS,
                )
        }
    }

    private fun launchJob(
        runner: RunnerPreparationResult.Ready,
        command: StartHeadlessJobCommand,
    ): AgentGatewayClient.HeadlessJob =
        runCatching {
            gateway.startHeadlessJob(
                workspace = runner.workspace,
                kind = command.kind,
                prompt = command.prompt,
                cliSessionId = null,
                timeoutSeconds = command.timeoutSeconds,
                stableSessionId = command.sessionId,
                epoch = 1,
                continuation = null,
            )
        }.getOrElse { ex ->
            throw AgentRunnerUnavailableException(
                workspaceId = runner.workspace.id,
                runnerStatus = "HeadlessLaunchFailed",
                cause = ex,
            )
        }

    private fun persistSession(
        command: StartHeadlessJobCommand,
        runner: RunnerPreparationResult.Ready,
        job: AgentGatewayClient.HeadlessJob,
    ) {
        val now = Instant.now()
        sessions.save(
            WorkspaceAgentSession(
                id = command.sessionId,
                workspaceId = runner.workspace.id,
                kind = command.kind,
                gatewayAgentId = job.id,
                status = WorkspaceAgentSessionStatus.RUNNING,
                createdAt = now,
                updatedAt = now,
                runMode = HEADLESS_RUN_MODE,
                currentSetupId = runner.setupId,
                currentSetupVersion = runner.setupVersion,
                epoch = 1,
                generation = 1,
            ),
        )
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

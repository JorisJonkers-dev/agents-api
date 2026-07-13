package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.observability.RunnerReprovisionTelemetry
import java.io.IOException
import java.time.Duration

/**
 * Records telemetry for runner session binding operations. Extracted from
 * RunnerSessionBinder to keep that class below the TooManyFunctions threshold.
 */
internal class RunnerBindingMetrics(
    private val telemetry: AgentsApiTelemetry,
) {
    fun observeBinding(
        operation: OperationLabel,
        mode: ModeLabel,
        block: () -> RunnerSessionBindingResult,
    ): RunnerSessionBindingResult {
        val startedAt = System.nanoTime()
        return runCatching(block)
            .onSuccess { result ->
                val (outcome, reason) =
                    when (result) {
                        is RunnerSessionBindingResult.Bound -> OutcomeLabel.SUCCESS to FailureReasonLabel.NONE
                        is RunnerSessionBindingResult.Conflict -> bindingConflict()
                        is RunnerSessionBindingResult.Unavailable ->
                            OutcomeLabel.FAILURE to FailureReasonLabel.UPSTREAM_UNAVAILABLE
                    }
                record(operation, mode, outcome, reason, startedAt)
            }.onFailure { ex ->
                record(operation, mode, OutcomeLabel.FAILURE, reasonClass(ex), startedAt)
            }.getOrThrow()
    }

    fun <T> observeStage(
        operation: OperationLabel,
        mode: ModeLabel,
        outcome: (T) -> Pair<OutcomeLabel, FailureReasonLabel> = { OutcomeLabel.SUCCESS to FailureReasonLabel.NONE },
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return runCatching(block)
            .onSuccess { result ->
                val (resultOutcome, reason) = outcome(result)
                record(operation, mode, resultOutcome, reason, startedAt)
            }.onFailure { ex ->
                record(operation, mode, OutcomeLabel.FAILURE, reasonClass(ex), startedAt)
            }.getOrThrow()
    }

    fun recordReprovision(
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
        startedAt: Long,
    ) {
        telemetry.recordRunnerReprovision(RunnerReprovisionTelemetry(outcome = outcome, reason = reason))
        record(
            operation = OperationLabel.REPROVISION_RUNNER,
            mode = ModeLabel.DURABLE,
            outcome = outcome,
            reason = reason,
            startedAt = startedAt,
        )
    }

    fun record(
        operation: OperationLabel,
        mode: ModeLabel,
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
        startedAt: Long,
    ) {
        telemetry.recordOperation(
            OperationTelemetry(
                operation = operation,
                mode = mode,
                outcome = outcome,
                reason = reason,
                duration = Duration.ofNanos((System.nanoTime() - startedAt).coerceAtLeast(0)),
            ),
        )
    }

    fun bindingConflict(): Pair<OutcomeLabel, FailureReasonLabel> = OutcomeLabel.FAILURE to FailureReasonLabel.CAPACITY

    fun reasonClass(ex: Throwable): FailureReasonLabel =
        when (ex) {
            is AgentSetupValidationException,
            is IllegalArgumentException,
            -> FailureReasonLabel.INVALID_REQUEST

            is AgentRunnerUnavailableException -> FailureReasonLabel.UPSTREAM_UNAVAILABLE
            is IOException -> FailureReasonLabel.IO_ERROR
            else -> FailureReasonLabel.UNKNOWN
        }
}

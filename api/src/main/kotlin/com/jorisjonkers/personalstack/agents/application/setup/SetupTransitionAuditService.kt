package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.port.SetupRestartEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class SetupTransitionAuditService(
    private val events: SetupRestartEventRepository,
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
) {
    data class Rejection(
        val workspace: Workspace,
        val session: WorkspaceAgentSession?,
        val targetId: AgentSetupId,
        val targetVersion: AgentSetupVersion,
        val result: AgentSetupValidationResult,
    )

    @Transactional
    fun recordRejected(
        rejection: Rejection,
        now: Instant = Instant.now(),
    ): SetupRestartEvent? {
        val startedAt = Instant.now()
        val result = rejection.result
        if (result.valid) {
            recordLifecycle(startedAt, OutcomeLabel.SKIPPED, FailureReasonLabel.NONE)
            return null
        }
        return runCatching {
            val saved = saveRejected(rejection, now)
            recordLifecycle(startedAt, OutcomeLabel.FAILURE, FailureReasonLabel.INVALID_REQUEST)
            saved
        }.getOrElse { ex ->
            recordLifecycle(startedAt, OutcomeLabel.FAILURE, ex.reasonClass())
            throw ex
        }
    }

    private fun saveRejected(
        rejection: Rejection,
        now: Instant,
    ): SetupRestartEvent {
        val workspace = rejection.workspace
        val session = rejection.session
        return events.save(
            SetupRestartEvent(
                id = UUID.randomUUID(),
                workspaceId = workspace.id,
                sessionId = session?.id,
                fromSetupId = session?.currentSetupId ?: workspace.currentRunnerSetupId,
                fromSetupVersion = session?.currentSetupVersion ?: workspace.currentRunnerSetupVersion,
                toSetupId = rejection.targetId,
                toSetupVersion = rejection.targetVersion,
                status = SetupRestartEventStatus.FAILED,
                reason = REASON_VALIDATION_REJECTED,
                message = rejection.result.message(),
                requestedAt = now,
                startedAt = null,
                completedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun recordLifecycle(
        startedAt: Instant,
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
    ) {
        telemetry.recordOperation(
            OperationTelemetry(
                operation = OperationLabel.REPROVISION_RUNNER,
                mode = ModeLabel.DURABLE,
                outcome = outcome,
                reason = reason,
                duration = Duration.between(startedAt, Instant.now()),
            ),
        )
    }

    private fun Throwable.reasonClass(): FailureReasonLabel =
        when (this) {
            is IOException -> FailureReasonLabel.IO_ERROR
            else -> FailureReasonLabel.UNKNOWN
        }

    companion object {
        const val REASON_VALIDATION_REJECTED = "validation-rejected"
    }
}

package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationInput
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.springframework.stereotype.Service
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class RestartAgentSessionService(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val binding: RunnerSessionBindingService,
    private val setupValidation: AgentSetupValidationService,
    private val clock: Clock = Clock.systemUTC(),
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
) {
    fun restart(request: RestartAgentSessionInput): RunnerSessionBindingResult {
        val startedAt = Instant.now()
        return runCatching { restartValidated(request, startedAt) }
            .getOrElse { ex ->
                recordLifecycle(startedAt, OutcomeLabel.FAILURE, ex.telemetryReason())
                throw ex
            }
    }

    private fun restartValidated(
        request: RestartAgentSessionInput,
        startedAt: Instant,
    ): RunnerSessionBindingResult {
        val workspace = requireWorkspace(request.workspaceId)
        val session = requireRestartableSession(request)
        val expectedGeneration = request.expectedGeneration ?: session.generation
        if (expectedGeneration != session.generation) {
            return RunnerSessionBindingResult
                .Conflict(current = session)
                .recorded(startedAt, FailureReasonLabel.CAPACITY)
        }
        require((request.targetSetupId == null) == (request.targetSetupVersion == null)) {
            "target setup id and version must be supplied together"
        }
        val targetSetupId = request.targetSetupId ?: session.currentSetupId
        val targetSetupVersion = request.targetSetupVersion ?: session.currentSetupVersion
        setupValidation.requireValid(
            AgentSetupValidationInput(
                workspace = workspace,
                targetId = targetSetupId,
                targetVersion = targetSetupVersion,
                session = session,
            ),
        )
        return binding
            .restart(
                RestartRunnerSessionBindingInput(
                    workspaceId = request.workspaceId,
                    sessionId = request.sessionId,
                    expectedGeneration = expectedGeneration,
                    reason = request.reason,
                    targetSetupId = targetSetupId,
                    targetSetupVersion = targetSetupVersion,
                ),
            ).recorded(startedAt, FailureReasonLabel.CAPACITY)
    }

    private fun requireWorkspace(workspaceId: WorkspaceId) =
        workspaces.findById(workspaceId)
            ?: throw NoSuchElementException("workspace not found: ${workspaceId.value}")

    private fun requireRestartableSession(request: RestartAgentSessionInput): WorkspaceAgentSession {
        val session =
            sessions.findById(request.sessionId)
                ?: throw NoSuchElementException("session not found: ${request.sessionId.value}")
        require(session.workspaceId == request.workspaceId) {
            "session does not belong to workspace: ${request.sessionId.value}"
        }
        require(session.runMode == INTERACTIVE_RUN_MODE) {
            "session is not interactive: ${request.sessionId.value}"
        }
        require(session.isRestartable(clock.instant())) {
            "session cannot be restarted: ${request.sessionId.value}"
        }
        return session
    }

    private fun RunnerSessionBindingResult.recorded(
        startedAt: Instant,
        conflictReason: FailureReasonLabel,
    ): RunnerSessionBindingResult {
        val outcome =
            when (this) {
                is RunnerSessionBindingResult.Bound -> OutcomeLabel.SUCCESS
                is RunnerSessionBindingResult.Conflict,
                is RunnerSessionBindingResult.Unavailable,
                -> OutcomeLabel.FAILURE
            }
        val reason =
            when (this) {
                is RunnerSessionBindingResult.Bound -> FailureReasonLabel.NONE
                is RunnerSessionBindingResult.Conflict -> conflictReason
                is RunnerSessionBindingResult.Unavailable -> FailureReasonLabel.UPSTREAM_UNAVAILABLE
            }
        recordLifecycle(startedAt, outcome, reason)
        return this
    }

    private fun recordLifecycle(
        startedAt: Instant,
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
    ) {
        telemetry.recordOperation(
            OperationTelemetry(
                operation = OperationLabel.REPROVISION_RUNNER,
                mode = ModeLabel.INTERACTIVE,
                outcome = outcome,
                reason = reason,
                duration = Duration.between(startedAt, Instant.now()),
            ),
        )
    }

    private fun Throwable.telemetryReason(): FailureReasonLabel =
        when (this) {
            is AgentSetupValidationException,
            is IllegalArgumentException,
            -> FailureReasonLabel.INVALID_REQUEST

            is AgentRunnerUnavailableException -> FailureReasonLabel.UPSTREAM_UNAVAILABLE
            is IOException -> FailureReasonLabel.IO_ERROR
            else -> FailureReasonLabel.UNKNOWN
        }

    private fun WorkspaceAgentSession.isRestartable(now: Instant): Boolean =
        when (status) {
            WorkspaceAgentSessionStatus.RUNNING,
            WorkspaceAgentSessionStatus.FAILED,
            -> true

            WorkspaceAgentSessionStatus.STOPPED ->
                retainedUntil?.isAfter(now) == true && cleanupRequestedAt == null

            WorkspaceAgentSessionStatus.STARTING -> false
        }

    companion object {
        const val INTERACTIVE_RUN_MODE = "INTERACTIVE"
    }
}

data class RestartAgentSessionInput(
    val workspaceId: WorkspaceId,
    val sessionId: WorkspaceAgentSessionId,
    val expectedGeneration: Long? = null,
    val reason: String? = "restart",
    val targetSetupId: AgentSetupId? = null,
    val targetSetupVersion: AgentSetupVersion? = null,
)

package com.jorisjonkers.personalstack.agents.infrastructure.ws

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.AgentKindLabel
import com.jorisjonkers.personalstack.agents.application.observability.AttachAttemptTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.observability.RunModeLabel
import com.jorisjonkers.personalstack.agents.application.sessionbinding.EnsureRunnerSessionBoundInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import java.time.Duration
import java.util.UUID

/**
 * Checks all preconditions required before opening a WebSocket bridge to the
 * agent-gateway. Extracted from SessionAttachHandler to keep that class below
 * the TooManyFunctions and LargeClass thresholds.
 */
internal class AttachPreconditionChecker(
    private val sessions: WorkspaceAgentSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val binding: RunnerSessionBindingService,
    private val telemetry: AgentsApiTelemetry,
) {
    /** Outcome of the full attach precondition check. */
    sealed interface AttachOutcome {
        /** All preconditions passed; the attach can proceed. */
        data class Ready(
            val sessionId: WorkspaceAgentSessionId,
            val workspace: Workspace,
            val gatewayAgentId: String,
            val gatewayEndpoint: String,
            val kind: AgentKindLabel,
        ) : AttachOutcome

        /** A precondition failed; close the client with this status. */
        data class Rejected(
            val reason: String,
            val status: CloseStatus,
            val failureReason: FailureReasonLabel,
            val kind: AgentKindLabel = AgentKindLabel.OTHER,
        ) : AttachOutcome
    }

    /**
     * Runs all attach preconditions (sessionId, session lookup,
     * rebind path, status, workspace, setup guards, gateway binding)
     * and returns either Ready (all pass) or Rejected (first failure).
     * Records telemetry and closes the client socket on rejection.
     * Returns null when the client was already rejected and closed.
     */
    fun resolveAttach(
        clientSession: WebSocketSession,
        sessionIdOf: (WebSocketSession) -> WorkspaceAgentSessionId?,
    ): AttachOutcome.Ready? {
        val outcome = checkPreconditions(clientSession, sessionIdOf)
        if (outcome is AttachOutcome.Rejected) {
            recordAttach(outcome.kind, OutcomeLabel.FAILURE, outcome.failureReason)
            clientSession.close(outcome.status.withReason(outcome.reason))
            return null
        }
        return outcome as AttachOutcome.Ready
    }

    private fun checkPreconditions(
        clientSession: WebSocketSession,
        sessionIdOf: (WebSocketSession) -> WorkspaceAgentSessionId?,
    ): AttachOutcome {
        val sessionId = sessionIdOf(clientSession)
        val agentSession = sessionId?.let { sessions.findById(it) }
        if (sessionId == null || agentSession == null) {
            return if (sessionId == null) {
                AttachOutcome.Rejected("malformed sessionId", CloseStatus.BAD_DATA, FailureReasonLabel.INVALID_REQUEST)
            } else {
                AttachOutcome.Rejected("unknown session", CloseStatus.BAD_DATA, FailureReasonLabel.NOT_FOUND)
            }
        }
        val kind = AgentKindLabel.fromRaw(agentSession.kind.name)
        var resolvedSession = agentSession
        var reboundWorkspace: Workspace? = null
        if (agentSession.status == WorkspaceAgentSessionStatus.RUNNING && agentSession.gatewayAgentId == null) {
            when (val result = binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = sessionId))) {
                is RunnerSessionBindingResult.Bound -> {
                    resolvedSession = result.session
                    reboundWorkspace = result.workspace
                }
                is RunnerSessionBindingResult.Conflict ->
                    return AttachOutcome.Rejected(
                        "session binding changed",
                        CloseStatus.SERVICE_RESTARTED,
                        FailureReasonLabel.OTHER,
                        kind,
                    )
                is RunnerSessionBindingResult.Unavailable ->
                    return AttachOutcome.Rejected(
                        "runner provisioning",
                        CloseStatus.SERVICE_RESTARTED,
                        FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                        kind,
                    )
            }
        }
        if (resolvedSession.status == WorkspaceAgentSessionStatus.STARTING) {
            return AttachOutcome.Rejected(
                "runner provisioning",
                CloseStatus.SERVICE_RESTARTED,
                FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                kind,
            )
        }
        val workspace =
            reboundWorkspace ?: workspaces.findById(resolvedSession.workspaceId)
                ?: return AttachOutcome.Rejected(
                    "workspace gone",
                    CloseStatus.SERVER_ERROR,
                    FailureReasonLabel.NOT_FOUND,
                    kind,
                )
        if (isSetupTransitionInProgress(resolvedSession, workspace)) {
            return AttachOutcome.Rejected(
                "runner setup transition",
                CloseStatus.SERVICE_RESTARTED,
                FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                kind,
            )
        }
        val gatewayAgentId =
            resolvedSession.gatewayAgentId
                ?: return AttachOutcome.Rejected(
                    "session not bound to a gateway agent",
                    CloseStatus.SERVER_ERROR,
                    FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                    kind,
                )
        return workspace.gatewayEndpoint?.let { endpoint ->
            AttachOutcome.Ready(sessionId, workspace, gatewayAgentId, endpoint, kind)
        } ?: AttachOutcome.Rejected(
            "workspace has no gateway endpoint",
            CloseStatus.SERVER_ERROR,
            FailureReasonLabel.UPSTREAM_UNAVAILABLE,
            kind,
        )
    }

    private fun isSetupTransitionInProgress(
        agentSession: com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession,
        workspace: Workspace,
    ): Boolean =
        agentSession.pendingSetupId != null ||
            agentSession.pendingSetupVersion != null ||
            workspace.runnerSetupOperation != RunnerSetupOperation.IDLE ||
            workspace.pendingRunnerSetupId != null ||
            workspace.pendingRunnerSetupVersion != null ||
            workspace.currentRunnerSetupId != agentSession.currentSetupId ||
            workspace.currentRunnerSetupVersion != agentSession.currentSetupVersion

    fun recordAttach(
        kind: AgentKindLabel,
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
    ) {
        val event =
            AttachAttemptTelemetry(
                kind = kind,
                runMode = RunModeLabel.INTERACTIVE,
                outcome = outcome,
                reason = reason,
            )
        telemetry.recordAttachAttempt(event)
        if (outcome == OutcomeLabel.FAILURE) telemetry.recordAttachFailure(event)
        telemetry.recordOperation(
            OperationTelemetry(
                operation = OperationLabel.ATTACH_SESSION,
                mode = ModeLabel.INTERACTIVE,
                outcome = outcome,
                reason = reason,
                duration = Duration.ZERO,
            ),
        )
    }

    companion object {
        fun sessionIdOf(session: WebSocketSession): WorkspaceAgentSessionId? {
            val match =
                Regex("/api/v1/ws/sessions/([^/]+)/attach").find(session.uri?.path ?: return null)
                    ?: return null
            return runCatching { WorkspaceAgentSessionId(UUID.fromString(match.groupValues[1])) }.getOrNull()
        }
    }
}

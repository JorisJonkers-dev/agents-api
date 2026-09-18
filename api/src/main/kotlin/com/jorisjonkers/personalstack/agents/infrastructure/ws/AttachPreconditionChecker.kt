package com.jorisjonkers.personalstack.agents.infrastructure.ws

import com.jorisjonkers.personalstack.agents.application.observability.AgentKindLabel
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
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
import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentSessionRepository
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
    private val sessions: AgentSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val binding: RunnerSessionBindingService,
    private val telemetry: AgentsApiTelemetry,
) {
    /** Outcome of the full attach precondition check. */
    sealed interface AttachOutcome {
        /** All preconditions passed; the attach can proceed. */
        data class Ready(
            val sessionId: AgentSessionId,
            val workspace: Workspace,
            val gatewayAgentId: String,
            // Null for a Shell Agent Session in a Scratch Workspace — there is
            // no runner Pod to open an upstream WebSocket to; [local] is true
            // and the attach is served in-container instead.
            val gatewayEndpoint: String?,
            val kind: AgentKindLabel,
            val local: Boolean = false,
        ) : AttachOutcome

        /** A precondition failed; close the client with this status. */
        data class Rejected(
            val reason: String,
            val status: CloseStatus,
            val failureReason: FailureReasonLabel,
            val kind: AgentKindLabel = AgentKindLabel.OTHER,
        ) : AttachOutcome
    }

    /** Result of the rebind-if-unbound step: either a usable session or a rejection. */
    private sealed interface RebindOutcome {
        data class Rebound(
            val session: AgentSession,
            val workspace: Workspace?,
        ) : RebindOutcome

        data class Rejected(
            val outcome: AttachOutcome.Rejected,
        ) : RebindOutcome
    }

    /**
     * Runs all attach preconditions (sessionId, session lookup,
     * rebind path, status, workspace, setup guards, gateway binding)
     * and returns either Ready (all pass) or null (rejected). Records
     * telemetry and closes the client socket on rejection.
     */
    fun resolveAttach(clientSession: WebSocketSession): AttachOutcome.Ready? {
        val outcome = checkPreconditions(clientSession)
        if (outcome is AttachOutcome.Rejected) {
            recordAttach(outcome.kind, OutcomeLabel.FAILURE, outcome.failureReason)
            clientSession.close(outcome.status.withReason(outcome.reason))
            return null
        }
        return outcome as AttachOutcome.Ready
    }

    private fun checkPreconditions(clientSession: WebSocketSession): AttachOutcome {
        val sessionId =
            sessionIdOf(clientSession)
                ?: return AttachOutcome.Rejected(
                    "malformed sessionId",
                    CloseStatus.BAD_DATA,
                    FailureReasonLabel.INVALID_REQUEST,
                )
        val agentSession =
            sessions.findById(sessionId)
                ?: return AttachOutcome.Rejected(
                    "unknown session",
                    CloseStatus.BAD_DATA,
                    FailureReasonLabel.NOT_FOUND,
                )
        val kind = AgentKindLabel.fromRaw(agentSession.kind.name)
        return when (val rebind = rebindIfUnbound(sessionId, agentSession, kind)) {
            is RebindOutcome.Rejected -> rebind.outcome
            is RebindOutcome.Rebound ->
                checkWorkspaceAndGateway(sessionId, rebind.session, rebind.workspace, kind)
        }
    }

    // A RUNNING session without a gateway binding (runner restarted
    // underneath it) is rebound through the binding service before the
    // attach proceeds, so the bridge always targets a live agent.
    private fun rebindIfUnbound(
        sessionId: AgentSessionId,
        agentSession: AgentSession,
        kind: AgentKindLabel,
    ): RebindOutcome {
        if (agentSession.status != AgentSessionStatus.RUNNING || agentSession.gatewayAgentId != null) {
            return RebindOutcome.Rebound(agentSession, workspace = null)
        }
        return when (val result = binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = sessionId))) {
            is RunnerSessionBindingResult.Bound -> RebindOutcome.Rebound(result.session, result.workspace)
            is RunnerSessionBindingResult.Conflict ->
                RebindOutcome.Rejected(
                    AttachOutcome.Rejected(
                        "session binding changed",
                        CloseStatus.SERVICE_RESTARTED,
                        FailureReasonLabel.OTHER,
                        kind,
                    ),
                )
            is RunnerSessionBindingResult.Unavailable ->
                RebindOutcome.Rejected(
                    AttachOutcome.Rejected(
                        "runner provisioning",
                        CloseStatus.SERVICE_RESTARTED,
                        FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                        kind,
                    ),
                )
        }
    }

    private fun checkWorkspaceAndGateway(
        sessionId: AgentSessionId,
        session: AgentSession,
        reboundWorkspace: Workspace?,
        kind: AgentKindLabel,
    ): AttachOutcome {
        if (session.status == AgentSessionStatus.STARTING) {
            return AttachOutcome.Rejected(
                "runner provisioning",
                CloseStatus.SERVICE_RESTARTED,
                FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                kind,
            )
        }
        val workspace =
            reboundWorkspace ?: workspaces.findById(session.workspaceId)
                ?: return AttachOutcome.Rejected(
                    "workspace gone",
                    CloseStatus.SERVER_ERROR,
                    FailureReasonLabel.NOT_FOUND,
                    kind,
                )
        return gatewayOutcome(sessionId, session, workspace, kind)
    }

    private fun gatewayOutcome(
        sessionId: AgentSessionId,
        session: AgentSession,
        workspace: Workspace,
        kind: AgentKindLabel,
    ): AttachOutcome {
        val gatewayAgentId = session.gatewayAgentId
        val endpoint = workspace.gatewayEndpoint
        val local = isInContainerSession(workspace)
        return when {
            isSetupTransitionInProgress(session, workspace) ->
                AttachOutcome.Rejected(
                    "runner setup transition",
                    CloseStatus.SERVICE_RESTARTED,
                    FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                    kind,
                )
            gatewayAgentId == null ->
                AttachOutcome.Rejected(
                    "session not bound to a gateway agent",
                    CloseStatus.SERVER_ERROR,
                    FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                    kind,
                )
            // A Scratch Workspace never has a runner Pod, so gatewayEndpoint is
            // always null for it — that is expected here, not a failure.
            endpoint == null && !local ->
                AttachOutcome.Rejected(
                    "workspace has no gateway endpoint",
                    CloseStatus.SERVER_ERROR,
                    FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                    kind,
                )
            else -> AttachOutcome.Ready(sessionId, workspace, gatewayAgentId, endpoint, kind, local)
        }
    }

    // Must mirror RunnerSessionBindingRouter.targetFor exactly. A session this
    // container binds has no gateway endpoint -- a Scratch Workspace never
    // provisions a Pod -- so a checker that disagrees rejects the attach with
    // "workspace has no gateway endpoint". For a Claude or Codex session that
    // is fatal rather than cosmetic: the terminal is the only place an Agent
    // Login can be created, so an unopenable one leaves the user with no way to
    // sign in at all (ADR 0002).
    private fun isInContainerSession(workspace: Workspace): Boolean =
        workspace.kind == WorkspaceKind.SCRATCH && workspace.podName == null

    private fun isSetupTransitionInProgress(
        agentSession: AgentSession,
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
        fun sessionIdOf(session: WebSocketSession): AgentSessionId? {
            val match =
                Regex("/api/v1/ws/sessions/([^/]+)/attach").find(session.uri?.path ?: return null)
                    ?: return null
            return runCatching { AgentSessionId(UUID.fromString(match.groupValues[1])) }.getOrNull()
        }
    }
}

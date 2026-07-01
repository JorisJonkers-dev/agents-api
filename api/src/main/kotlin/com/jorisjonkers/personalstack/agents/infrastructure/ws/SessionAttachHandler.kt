@file:Suppress("TooManyFunctions", "LargeClass")

package com.jorisjonkers.personalstack.agents.infrastructure.ws

import com.jorisjonkers.personalstack.agents.application.idle.ConnectedClientTracker
import com.jorisjonkers.personalstack.agents.application.idle.WorkspaceActivityTracker
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
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import jakarta.websocket.ContainerProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.AbstractWebSocketHandler
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WebSocket bridge: browser <-> agents-api <-> agent-gateway.
 *
 * Per browser-attach, this handler:
 *   1. Opens an upstream WS to the runner's gateway
 *      `/ws/agents/{gatewayAgentId}/attach`.
 *   2. Relays each inbound client frame to the upstream verbatim —
 *      `{"input"}` keystrokes and `{"resize":{"cols","rows"}}` PTY
 *      resizes alike. The browser xterm is the source of truth for
 *      both.
 *   3. Relays each upstream frame to the browser verbatim.
 *
 * The terminal stream is NOT persisted as Turns: the live xterm WS
 * already shows it and the terminal holds screen state, so buffering
 * raw PTY bytes (including ANSI escapes) into the transcript only
 * duplicated the display and wrote escape garbage into the DB.
 */
@Component
class SessionAttachDependencies(
    val sessions: WorkspaceAgentSessionRepository,
    val workspaces: WorkspaceRepository,
    val activity: WorkspaceActivityTracker,
    val connected: ConnectedClientTracker,
    val binding: RunnerSessionBindingService,
    val sessionStatus: SessionStatusPublisher,
)

@Component
class SessionAttachHandler(
    dependencies: SessionAttachDependencies,
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
) : AbstractWebSocketHandler() {
    private val sessions = dependencies.sessions
    private val workspaces = dependencies.workspaces
    private val activity = dependencies.activity
    private val connected = dependencies.connected
    private val binding = dependencies.binding
    private val sessionStatus = dependencies.sessionStatus
    private val log = LoggerFactory.getLogger(SessionAttachHandler::class.java)

    private data class Bridge(
        val sessionId: WorkspaceAgentSessionId,
        val workspaceId: WorkspaceId,
        val upstream: WebSocketSession,
    )

    private val bridges = ConcurrentHashMap<String, Bridge>()

    // The gateway streams terminal output as bounded frames (LogTailer
    // MAX_CHUNK_CHARS); a screenful of a TUI's ANSI escapes can JSON-encode
    // to tens of KiB, well past the container's 8 KiB default. Reassembling
    // a frame larger than the buffer fails the read and drops the socket, so
    // raise the inbound text/binary limits on the client that bridges to the
    // gateway. The browser leg is sent fragmented and has no such limit.
    private val client =
        StandardWebSocketClient(
            ContainerProvider.getWebSocketContainer().apply {
                defaultMaxTextMessageBufferSize = UPSTREAM_MAX_MESSAGE_BYTES
                defaultMaxBinaryMessageBufferSize = UPSTREAM_MAX_MESSAGE_BYTES
            },
        )

    private data class ResolvedAttach(
        val sessionId: WorkspaceAgentSessionId,
        val workspaceId: WorkspaceId,
        val upstreamUri: URI,
        val kind: AgentKindLabel,
    )

    private data class BrowserCursor(
        val epoch: String?,
        val offset: String?,
    )

    /**
     * Resolves the client WS into the upstream URI plus the
     * workspace id we need to mark "active". A null on any leg of
     * the resolution means we close the client WS with a labelled
     * status; the chain reads cleaner with explicit guards than
     * with a flattened let/Result alternative, so detekt's bounded-
     * branch and return rules are suppressed with intent.
     *
     * Explicit attach guards preserve the close reason for each failure.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "ComplexCondition")
    private fun resolveAttach(clientSession: WebSocketSession): ResolvedAttach? {
        val sessionId =
            sessionIdOf(clientSession)
                ?: return closeAndReturn(
                    clientSession,
                    "malformed sessionId",
                    CloseStatus.BAD_DATA,
                    FailureReasonLabel.INVALID_REQUEST,
                )
        var agentSession =
            sessions.findById(sessionId)
                ?: return closeAndReturn(
                    clientSession,
                    "unknown session",
                    CloseStatus.BAD_DATA,
                    FailureReasonLabel.NOT_FOUND,
                )
        val kind = AgentKindLabel.fromRaw(agentSession.kind.name)
        var reboundWorkspace: Workspace? = null
        if (agentSession.status == WorkspaceAgentSessionStatus.RUNNING && agentSession.gatewayAgentId == null) {
            when (val result = binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = sessionId))) {
                is RunnerSessionBindingResult.Bound -> {
                    agentSession = result.session
                    reboundWorkspace = result.workspace
                }

                is RunnerSessionBindingResult.Conflict ->
                    return closeAndReturn(
                        clientSession,
                        "session binding changed",
                        CloseStatus.SERVICE_RESTARTED,
                        FailureReasonLabel.OTHER,
                        kind,
                    )
                is RunnerSessionBindingResult.Unavailable ->
                    return closeAndReturn(
                        clientSession,
                        "runner provisioning",
                        CloseStatus.SERVICE_RESTARTED,
                        FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                        kind,
                    )
            }
        }
        if (agentSession.status == WorkspaceAgentSessionStatus.STARTING) {
            return closeAndReturn(
                clientSession,
                "runner provisioning",
                CloseStatus.SERVICE_RESTARTED,
                FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                kind,
            )
        }
        val workspace =
            reboundWorkspace ?: workspaces.findById(agentSession.workspaceId)
                ?: return closeAndReturn(
                    clientSession,
                    "workspace gone",
                    CloseStatus.SERVER_ERROR,
                    FailureReasonLabel.NOT_FOUND,
                    kind,
                )
        if (
            agentSession.pendingSetupId != null ||
            agentSession.pendingSetupVersion != null ||
            workspace.hasRunnerSetupGuard() ||
            workspace.currentRunnerSetupId != agentSession.currentSetupId ||
            workspace.currentRunnerSetupVersion != agentSession.currentSetupVersion
        ) {
            return closeAndReturn(
                clientSession,
                "runner setup transition",
                CloseStatus.SERVICE_RESTARTED,
                FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                kind,
            )
        }
        val gatewayAgentId =
            agentSession.gatewayAgentId
                ?: return closeAndReturn(
                    clientSession,
                    "session not bound to a gateway agent",
                    CloseStatus.SERVER_ERROR,
                    FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                    kind,
                )
        val gatewayBase =
            workspace.gatewayEndpoint
                ?: return closeAndReturn(
                    clientSession,
                    "workspace has no gateway endpoint",
                    CloseStatus.SERVER_ERROR,
                    FailureReasonLabel.UPSTREAM_UNAVAILABLE,
                    kind,
                )
        val cursor = browserCursorOf(clientSession)
        val upstreamUri = upstreamUri(gatewayBase, gatewayAgentId, cursor)
        return ResolvedAttach(sessionId, workspace.id, upstreamUri, kind)
    }

    private fun closeAndReturn(
        session: WebSocketSession,
        reason: String,
        status: CloseStatus,
        failureReason: FailureReasonLabel,
        kind: AgentKindLabel = AgentKindLabel.OTHER,
    ): ResolvedAttach? {
        recordAttach(kind, OutcomeLabel.FAILURE, failureReason)
        session.close(status.withReason(reason))
        return null
    }

    @Suppress("LongMethod") // attach handshake and bridge registration must stay ordered
    override fun afterConnectionEstablished(clientSession: WebSocketSession) {
        val resolved = resolveAttach(clientSession) ?: return
        val upstreamHandler =
            UpstreamHandler(
                client = clientSession,
                sessionId = resolved.sessionId,
                workspaceId = resolved.workspaceId,
                relay =
                    UpstreamRelayDependencies(
                        sessions = sessions,
                        activity = activity,
                        binding = binding,
                        sessionStatus = sessionStatus,
                    ),
                telemetry = telemetry,
            )
        val upstream =
            runCatching {
                client
                    .execute(upstreamHandler, resolved.upstreamUri.toString())
                    .get(UPSTREAM_HANDSHAKE_SECONDS, TimeUnit.SECONDS)
            }.getOrElse {
                recordAttach(resolved.kind, OutcomeLabel.FAILURE, FailureReasonLabel.UPSTREAM_UNAVAILABLE)
                clientSession.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach unavailable"))
                return
            }
        recordAttach(resolved.kind, OutcomeLabel.SUCCESS, FailureReasonLabel.NONE)
        bridges[clientSession.id] = Bridge(resolved.sessionId, resolved.workspaceId, upstream)
        connected.attach(resolved.workspaceId)
        activity.touch(resolved.workspaceId)
        log.info(
            "attached client {} to session {} via {}",
            clientSession.id,
            resolved.sessionId,
            resolved.upstreamUri,
        )
    }

    override fun handleTextMessage(
        clientSession: WebSocketSession,
        message: TextMessage,
    ) = relayToUpstream(clientSession, message)

    override fun handleBinaryMessage(
        session: WebSocketSession,
        message: BinaryMessage,
    ) = relayToUpstream(session, message)

    override fun handlePongMessage(
        session: WebSocketSession,
        message: PongMessage,
    ) = relayToUpstream(session, message)

    private fun relayToUpstream(
        clientSession: WebSocketSession,
        message: WebSocketMessage<*>,
    ) {
        val bridge = bridges[clientSession.id] ?: return
        if (bridge.upstream.isOpen) {
            runCatching {
                synchronized(bridge.upstream) { bridge.upstream.sendMessage(message) }
            }.onFailure {
                recordAttachOperation(OutcomeLabel.FAILURE, FailureReasonLabel.IO_ERROR)
                closeBridge(
                    clientSession,
                    bridge.upstream,
                    CloseStatus.SERVICE_RESTARTED.withReason("runner attach error"),
                )
                return
            }
        } else {
            recordAttachOperation(OutcomeLabel.FAILURE, FailureReasonLabel.UPSTREAM_UNAVAILABLE)
            clientSession.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach disconnected"))
        }
        sessions.findById(bridge.sessionId)?.workspaceId?.let { activity.touch(it) }
    }

    override fun afterConnectionClosed(
        clientSession: WebSocketSession,
        status: CloseStatus,
    ) {
        val bridge = bridges.remove(clientSession.id) ?: return
        connected.detach(bridge.workspaceId)
        runCatching { bridge.upstream.close(status) }
        recordAttachOperation(outcomeOf(status), failureReasonOf(status))
    }

    private fun closeBridge(
        client: WebSocketSession,
        upstream: WebSocketSession,
        status: CloseStatus,
    ) {
        runCatching {
            if (client.isOpen) client.close(status)
        }
        runCatching {
            if (upstream.isOpen) upstream.close(status)
        }
    }

    private fun sessionIdOf(session: WebSocketSession): WorkspaceAgentSessionId? {
        val match =
            Regex("/api/v1/ws/sessions/([^/]+)/attach").find(session.uri?.path ?: return null)
                ?: return null
        return runCatching { WorkspaceAgentSessionId(UUID.fromString(match.groupValues[1])) }.getOrNull()
    }

    private fun browserCursorOf(session: WebSocketSession): BrowserCursor {
        val query = queryOf(session)
        return BrowserCursor(
            epoch = nonNegativeInteger(query["epoch"]),
            offset = nonNegativeInteger(query["offset"]),
        )
    }

    private fun queryOf(session: WebSocketSession): Map<String, String> =
        session.uri
            ?.rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                val key = decodeQuery(pieces[0])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = if (pieces.size == 2) decodeQuery(pieces[1]) ?: return@mapNotNull null else ""
                key to value
            }?.toMap()
            .orEmpty()

    private fun decodeQuery(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrNull()

    private fun nonNegativeInteger(value: String?): String? =
        value?.takeIf { it.matches(NON_NEGATIVE_INTEGER) && it.toLongOrNull() != null }

    private fun Workspace.hasRunnerSetupGuard(): Boolean =
        runnerSetupOperation != RunnerSetupOperation.IDLE ||
            pendingRunnerSetupId != null ||
            pendingRunnerSetupVersion != null

    private fun upstreamUri(
        gatewayBase: String,
        gatewayAgentId: String,
        cursor: BrowserCursor,
    ): URI {
        val base = URI.create(gatewayBase)
        val scheme =
            when (base.scheme) {
                "http" -> "ws"
                "https" -> "wss"
                else -> base.scheme
            }
        val builder =
            UriComponentsBuilder
                .fromUri(base)
                .scheme(scheme)
                .replaceQuery(null)
                .pathSegment("ws", "agents", gatewayAgentId, "attach")
        cursor.epoch?.let { builder.queryParam("epoch", it) }
        cursor.offset?.let { builder.queryParam("offset", it) }
        return builder.build().toUri()
    }

    companion object {
        private const val UPSTREAM_HANDSHAKE_SECONDS = 5L

        // Generous headroom over the gateway's largest JSON-wrapped output
        // frame so a screenful of ANSI escapes never overruns the reassembly
        // buffer. Allocated lazily per session by the container.
        private const val UPSTREAM_MAX_MESSAGE_BYTES = 1024 * 1024

        private val NON_NEGATIVE_INTEGER = Regex("""\d+""")

        private fun outcomeOf(status: CloseStatus): OutcomeLabel =
            when (status.code) {
                CloseStatus.NORMAL.code -> OutcomeLabel.SUCCESS
                CloseStatus.GOING_AWAY.code -> OutcomeLabel.CANCELLED
                else -> OutcomeLabel.FAILURE
            }

        private fun failureReasonOf(status: CloseStatus): FailureReasonLabel =
            when (status.code) {
                CloseStatus.NORMAL.code -> FailureReasonLabel.NONE
                CloseStatus.GOING_AWAY.code -> FailureReasonLabel.CANCELLED
                CloseStatus.BAD_DATA.code -> FailureReasonLabel.INVALID_REQUEST
                CloseStatus.SERVICE_RESTARTED.code -> FailureReasonLabel.UPSTREAM_UNAVAILABLE
                else -> FailureReasonLabel.OTHER
            }
    }

    private fun recordAttach(
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
        recordAttachOperation(outcome, reason)
    }

    private fun recordAttachOperation(
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
    ) {
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

    /**
     * Inbound from gateway: shovel the frame straight back to the
     * browser and record activity so the idle sweep knows the AI is
     * still producing output (even if the user is not typing).
     */
    private data class UpstreamRelayDependencies(
        val sessions: WorkspaceAgentSessionRepository,
        val activity: WorkspaceActivityTracker,
        val binding: RunnerSessionBindingService,
        val sessionStatus: SessionStatusPublisher,
    )

    private class UpstreamHandler(
        private val client: WebSocketSession,
        private val sessionId: WorkspaceAgentSessionId,
        private val workspaceId: WorkspaceId,
        private val relay: UpstreamRelayDependencies,
        private val telemetry: AgentsApiTelemetry,
    ) : AbstractWebSocketHandler() {
        private val sessions = relay.sessions
        private val activity = relay.activity
        private val binding = relay.binding
        private val sessionStatus = relay.sessionStatus

        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) = relayToClient(session, message)

        override fun handleBinaryMessage(
            session: WebSocketSession,
            message: BinaryMessage,
        ) = relayToClient(session, message)

        override fun handlePongMessage(
            session: WebSocketSession,
            message: PongMessage,
        ) = relayToClient(session, message)

        private fun relayToClient(
            upstream: WebSocketSession,
            message: WebSocketMessage<*>,
        ) {
            if (client.isOpen) {
                runCatching {
                    synchronized(client) { client.sendMessage(message) }
                }.onFailure {
                    recordAttachOperation(OutcomeLabel.FAILURE, FailureReasonLabel.IO_ERROR)
                    runCatching { upstream.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach error")) }
                    runCatching { client.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach error")) }
                    return
                }
            }
            // Touch on upstream output: AI streaming → keeps idle timer alive
            // while the agent is working, even with no user keystrokes.
            activity.touch(workspaceId)
        }

        override fun afterConnectionClosed(
            session: WebSocketSession,
            status: CloseStatus,
        ) {
            if (status.code == CloseStatus.BAD_DATA.code && status.reason == "unknown agent") {
                runCatching {
                    sessions.findById(sessionId)?.let {
                        val now = Instant.now()
                        val cleared = sessions.clearGatewayBindingIfGeneration(sessionId, it.generation, now)
                        if (cleared) sessionStatus.publishStatus(it.clearGatewayBinding(now))
                    }
                    binding.ensureBound(
                        EnsureRunnerSessionBoundInput(sessionId = sessionId, workspaceId = workspaceId),
                    )
                }
            }
            runCatching {
                if (client.isOpen) {
                    client.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach disconnected"))
                }
            }
            recordAttachOperation(outcomeOf(status), failureReasonOf(status))
        }

        override fun handleTransportError(
            session: WebSocketSession,
            exception: Throwable,
        ) {
            runCatching {
                if (client.isOpen) {
                    client.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach error"))
                }
            }
            runCatching {
                if (session.isOpen) {
                    session.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach error"))
                }
            }
            recordAttachOperation(OutcomeLabel.FAILURE, FailureReasonLabel.IO_ERROR)
        }

        private fun recordAttachOperation(
            outcome: OutcomeLabel,
            reason: FailureReasonLabel,
        ) {
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
    }
}

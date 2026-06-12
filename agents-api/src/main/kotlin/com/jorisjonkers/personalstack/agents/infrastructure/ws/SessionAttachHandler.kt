package com.jorisjonkers.personalstack.agents.infrastructure.ws

import com.jorisjonkers.personalstack.agents.application.idle.ConnectedClientTracker
import com.jorisjonkers.personalstack.agents.application.idle.WorkspaceActivityTracker
import com.jorisjonkers.personalstack.agents.application.sessionbinding.EnsureRunnerSessionBoundInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
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
class SessionAttachHandler(
    private val sessions: WorkspaceAgentSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val activity: WorkspaceActivityTracker,
    private val connected: ConnectedClientTracker,
    private val binding: RunnerSessionBindingService,
    private val sessionStatus: SessionStatusPublisher,
) : AbstractWebSocketHandler() {
    private val log = LoggerFactory.getLogger(SessionAttachHandler::class.java)

    private data class Bridge(
        val sessionId: WorkspaceAgentSessionId,
        val workspaceId: com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId,
        val upstream: WebSocketSession,
    )

    private val bridges = ConcurrentHashMap<String, Bridge>()
    private val client = StandardWebSocketClient()

    private data class ResolvedAttach(
        val sessionId: WorkspaceAgentSessionId,
        val workspaceId: com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId,
        val upstreamUri: URI,
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
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun resolveAttach(clientSession: WebSocketSession): ResolvedAttach? {
        val sessionId =
            sessionIdOf(clientSession)
                ?: return closeAndReturn(clientSession, "malformed sessionId", CloseStatus.BAD_DATA)
        var agentSession =
            sessions.findById(sessionId)
                ?: return closeAndReturn(clientSession, "unknown session", CloseStatus.BAD_DATA)
        var reboundWorkspace: Workspace? = null
        if (agentSession.status == WorkspaceAgentSessionStatus.RUNNING && agentSession.gatewayAgentId == null) {
            when (val result = binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = sessionId))) {
                is RunnerSessionBindingResult.Bound -> {
                    agentSession = result.session
                    reboundWorkspace = result.workspace
                }

                is RunnerSessionBindingResult.Conflict ->
                    return closeAndReturn(clientSession, "session binding changed", CloseStatus.SERVICE_RESTARTED)
                is RunnerSessionBindingResult.Unavailable ->
                    return closeAndReturn(clientSession, "runner provisioning", CloseStatus.SERVICE_RESTARTED)
            }
        }
        if (agentSession.status == WorkspaceAgentSessionStatus.STARTING) {
            return closeAndReturn(clientSession, "runner provisioning", CloseStatus.SERVICE_RESTARTED)
        }
        val workspace =
            reboundWorkspace ?: workspaces.findById(agentSession.workspaceId)
                ?: return closeAndReturn(clientSession, "workspace gone", CloseStatus.SERVER_ERROR)
        val gatewayAgentId =
            agentSession.gatewayAgentId
                ?: return closeAndReturn(
                    clientSession,
                    "session not bound to a gateway agent",
                    CloseStatus.SERVER_ERROR,
                )
        val gatewayBase =
            workspace.gatewayEndpoint
                ?: return closeAndReturn(clientSession, "workspace has no gateway endpoint", CloseStatus.SERVER_ERROR)
        val cursor = browserCursorOf(clientSession)
        val upstreamUri = upstreamUri(gatewayBase, gatewayAgentId, cursor)
        return ResolvedAttach(sessionId, workspace.id, upstreamUri)
    }

    private fun closeAndReturn(
        session: WebSocketSession,
        reason: String,
        status: CloseStatus,
    ): ResolvedAttach? {
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
                sessions = sessions,
                activity = activity,
                binding = binding,
                sessionStatus = sessionStatus,
            )
        val upstream =
            runCatching {
                client
                    .execute(upstreamHandler, resolved.upstreamUri.toString())
                    .get(UPSTREAM_HANDSHAKE_SECONDS, TimeUnit.SECONDS)
            }.getOrElse {
                clientSession.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach unavailable"))
                return
            }
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
            synchronized(bridge.upstream) { bridge.upstream.sendMessage(message) }
        } else {
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
            ?: emptyMap()

    private fun decodeQuery(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrNull()

    private fun nonNegativeInteger(value: String?): String? =
        value?.takeIf { it.matches(NON_NEGATIVE_INTEGER) && it.toLongOrNull() != null }

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
        private val NON_NEGATIVE_INTEGER = Regex("""\d+""")
    }

    /**
     * Inbound from gateway: shovel the frame straight back to the
     * browser and record activity so the idle sweep knows the AI is
     * still producing output (even if the user is not typing).
     */
    private class UpstreamHandler(
        private val client: WebSocketSession,
        private val sessionId: WorkspaceAgentSessionId,
        private val workspaceId: com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId,
        private val sessions: WorkspaceAgentSessionRepository,
        private val activity: WorkspaceActivityTracker,
        private val binding: RunnerSessionBindingService,
        private val sessionStatus: SessionStatusPublisher,
    ) : AbstractWebSocketHandler() {
        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) = relayToClient(message)

        override fun handleBinaryMessage(
            session: WebSocketSession,
            message: BinaryMessage,
        ) = relayToClient(message)

        override fun handlePongMessage(
            session: WebSocketSession,
            message: PongMessage,
        ) = relayToClient(message)

        private fun relayToClient(message: WebSocketMessage<*>) {
            if (client.isOpen) {
                synchronized(client) { client.sendMessage(message) }
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
            if (client.isOpen) {
                client.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach disconnected"))
            }
        }

        override fun handleTransportError(
            session: WebSocketSession,
            exception: Throwable,
        ) {
            if (client.isOpen) {
                client.close(CloseStatus.SERVICE_RESTARTED.withReason("runner attach error"))
            }
        }
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.ws

import com.jorisjonkers.personalstack.agents.application.idle.ConnectedClientTracker
import com.jorisjonkers.personalstack.agents.application.idle.WorkspaceActivityTracker
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
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
) : TextWebSocketHandler() {
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

    /**
     * Resolves the client WS into the upstream URI plus the
     * workspace id we need to mark "active". A null on any leg of
     * the resolution means we close the client WS with a labelled
     * status; the chain reads cleaner with explicit guards than
     * with a flattened let/Result alternative, so detekt's bounded-
     * return rule is suppressed with intent.
     */
    @Suppress("ReturnCount")
    private fun resolveAttach(clientSession: WebSocketSession): ResolvedAttach? {
        val sessionId =
            sessionIdOf(clientSession)
                ?: return closeAndReturn(clientSession, "missing sessionId", CloseStatus.BAD_DATA)
        val agentSession =
            sessions.findById(sessionId)
                ?: return closeAndReturn(clientSession, "unknown session", CloseStatus.BAD_DATA)
        val workspace =
            workspaces.findById(agentSession.workspaceId)
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
        val upstreamUri = URI.create(gatewayBase.replaceFirst("http", "ws") + "/ws/agents/$gatewayAgentId/attach")
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

    override fun afterConnectionEstablished(clientSession: WebSocketSession) {
        val resolved = resolveAttach(clientSession) ?: return
        val upstreamHandler = UpstreamHandler(clientSession, resolved.workspaceId, activity)
        val upstream =
            client
                .execute(upstreamHandler, resolved.upstreamUri.toString())
                .get(UPSTREAM_HANDSHAKE_SECONDS, TimeUnit.SECONDS)
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
            Regex("/api/v1/ws/sessions/([0-9a-f-]{36})/attach").find(session.uri?.path ?: return null)
                ?: return null
        return runCatching { WorkspaceAgentSessionId(UUID.fromString(match.groupValues[1])) }.getOrNull()
    }

    companion object {
        private const val UPSTREAM_HANDSHAKE_SECONDS = 5L
    }

    /**
     * Inbound from gateway: shovel the frame straight back to the
     * browser and record activity so the idle sweep knows the AI is
     * still producing output (even if the user is not typing).
     */
    private class UpstreamHandler(
        private val client: WebSocketSession,
        private val workspaceId: com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId,
        private val activity: WorkspaceActivityTracker,
    ) : TextWebSocketHandler() {
        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) {
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

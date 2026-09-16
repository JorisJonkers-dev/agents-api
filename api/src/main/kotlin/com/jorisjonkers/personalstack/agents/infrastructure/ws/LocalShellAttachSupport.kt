package com.jorisjonkers.personalstack.agents.infrastructure.ws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.infrastructure.shell.LogTailer
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellAttachOperations
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves a browser Attach to an in-container Shell Agent Session
 * directly — agents-api is the producer of the `{"output"}` /
 * `{"input"}` / `{"resize"}` frames here instead of relaying them to a
 * runner Pod's gateway over a second WebSocket hop
 * ([SessionAttachHandler] does that relay for the Pod path). The JSON
 * envelope is unchanged from agent-gateway's, so agents-ui needs no
 * change to Attach to either kind of Agent Session.
 */
@Component
class LocalShellAttachSupport(
    private val ops: ShellAttachOperations,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(LocalShellAttachSupport::class.java)

    private data class Active(
        val workspace: Workspace,
        val gatewayAgentId: String,
        val tailer: AutoCloseable,
    )

    private val active = ConcurrentHashMap<String, Active>()

    fun attach(
        clientSession: WebSocketSession,
        workspace: Workspace,
        gatewayAgentId: String,
    ) {
        val snapshot = ops.snapshot(workspace, gatewayAgentId)
        LogTailer.chunked(snapshot, LogTailer.MAX_CHUNK_CHARS) { sendOutput(clientSession, it) }
        val tailer = ops.startTailer(workspace, gatewayAgentId) { text -> sendOutput(clientSession, text) }
        active[clientSession.id] = Active(workspace, gatewayAgentId, tailer)
    }

    fun isActive(clientSessionId: String): Boolean = active.containsKey(clientSessionId)

    fun handleText(
        clientSession: WebSocketSession,
        message: TextMessage,
    ) {
        val session = active[clientSession.id] ?: return
        val node = runCatching { mapper.readTree(message.payload) }.getOrNull() ?: return
        val resize = node.get("resize")
        if (resize != null) {
            handleResize(session, resize)
        } else {
            handleInput(session, node)
        }
    }

    private fun handleResize(
        session: Active,
        resize: JsonNode,
    ) {
        val cols = resize.get("cols")?.takeIf { it.isNumber }?.asInt() ?: return
        val rows = resize.get("rows")?.takeIf { it.isNumber }?.asInt() ?: return
        runCatching { ops.resize(session.workspace, session.gatewayAgentId, cols, rows) }
            .onFailure { log.warn("resize of shell agent {} failed: {}", session.gatewayAgentId, it.message) }
    }

    private fun handleInput(
        session: Active,
        node: JsonNode,
    ) {
        val input = node.get("input")?.takeIf { it.isTextual }?.asText() ?: return
        val enter = node.get("enter")?.takeIf { it.isBoolean }?.asBoolean() ?: true
        runCatching { ops.sendInput(session.workspace, session.gatewayAgentId, input, enter) }
            .onFailure { log.warn("input to shell agent {} failed: {}", session.gatewayAgentId, it.message) }
    }

    fun detach(clientSessionId: String) {
        active.remove(clientSessionId)?.let { session ->
            runCatching { session.tailer.close() }
                .onFailure { log.warn("closing shell tailer for {} failed: {}", clientSessionId, it.message) }
        }
    }

    private fun sendOutput(
        clientSession: WebSocketSession,
        text: String,
    ) {
        if (text.isEmpty() || !clientSession.isOpen) return
        val json = mapper.writeValueAsString(mapOf("output" to text))
        runCatching {
            synchronized(clientSession) { clientSession.sendMessage(TextMessage(json)) }
        }.onFailure { log.warn("sending shell output to {} failed: {}", clientSession.id, it.message) }
    }
}

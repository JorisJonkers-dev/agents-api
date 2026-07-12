package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.agents.config.ChatGenerationProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.ChatGenerationPort
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

/**
 * [ChatGenerationPort] adapter that routes a chat prompt through a headless
 * agent job on the runner Pod via the shared [AgentGatewayClient].
 *
 * Flow:
 * 1. Resolve the target workspace via [ChatGenerationProperties.runnerPodWorkspaceId].
 * 2. Submit the job via [AgentGatewayClient.startHeadlessJob] with the configured
 *    [ChatGenerationProperties.runnerPodAgentKind]; receive back the job id.
 * 3. GET `{gatewayEndpoint}/agents/headless/{id}/stream` as text/event-stream;
 *    parse Spring SSE format (event:/data: pairs) and delegate each line to
 *    [parseStreamJsonLine] to extract assistant text chunks.
 *
 * Using the shared gateway client (step 2) ensures the job is submitted through
 * the same typed request path as [StartHeadlessJobCommandHandler], so
 * [AgentGatewayClient.HeadlessJobRequest.enableKbHooks] and any future
 * request-level fields are honoured uniformly.
 *
 * Active only when `chat.generation.backend=runner-pod`.
 */
@Component
@ConditionalOnProperty(
    prefix = "chat.generation",
    name = ["backend"],
    havingValue = "runner-pod",
)
class RunnerPodChatGenerator(
    private val gateway: AgentGatewayClient,
    private val restClient: RestClient,
    private val workspaces: WorkspaceRepository,
    private val props: ChatGenerationProperties,
) : ChatGenerationPort {
    private val log = LoggerFactory.getLogger(RunnerPodChatGenerator::class.java)

    override fun generate(
        prompt: String,
        onChunk: (String) -> Unit,
    ): String {
        val workspace = resolveWorkspace() ?: return ""
        val jobId = startHeadlessJob(workspace, prompt) ?: return ""
        return streamJobOutput(workspace.gatewayEndpoint!!, jobId, onChunk)
    }

    private fun resolveWorkspace(): com.jorisjonkers.personalstack.agents.domain.model.Workspace? {
        val rawId = props.runnerPodWorkspaceId
        if (rawId.isNullOrBlank()) {
            log.warn("chat.generation.runner-pod-workspace-id is not configured — no answer produced")
            return null
        }
        return runCatching {
            val workspaceId = WorkspaceId(UUID.fromString(rawId))
            val workspace = workspaces.findById(workspaceId)
            if (workspace == null) {
                log.warn("RunnerPodChatGenerator: workspace {} not found — no answer produced", rawId)
                return@runCatching null
            }
            if (workspace.gatewayEndpoint.isNullOrBlank()) {
                log.warn(
                    "RunnerPodChatGenerator: workspace {} has no gateway endpoint — no answer produced",
                    rawId,
                )
                return@runCatching null
            }
            workspace
        }.onFailure { ex ->
            log.warn("RunnerPodChatGenerator: failed to resolve workspace {}: {}", rawId, ex.message)
        }.getOrNull()
    }

    private fun startHeadlessJob(
        workspace: com.jorisjonkers.personalstack.agents.domain.model.Workspace,
        prompt: String,
    ): String? =
        runCatching {
            val job =
                gateway.startHeadlessJob(
                    AgentGatewayClient.HeadlessJobRequest(
                        workspace = workspace,
                        kind = props.runnerPodAgentKind,
                        prompt = prompt,
                        enableKbHooks = props.runnerPodEnableKbHooks,
                    ),
                )
            job.id
        }.onFailure { ex ->
            log.warn("RunnerPodChatGenerator: failed to start headless job: {}", ex.message)
        }.getOrNull()

    private fun streamJobOutput(
        gatewayEndpoint: String,
        jobId: String,
        onChunk: (String) -> Unit,
    ): String {
        val accumulator = SseChatAccumulator(onChunk)
        runCatching {
            restClient
                .get()
                .uri("$gatewayEndpoint/agents/headless/$jobId/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange { _, response ->
                    response.body.bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.forEachLine(accumulator::consume)
                    }
                }
        }.onFailure { ex ->
            log.warn("RunnerPodChatGenerator: SSE stream for job {} failed: {}", jobId, ex.message)
        }
        return accumulator.answer()
    }

    /**
     * Accumulates a Spring SSE stream (event:/data: line pairs). For `line`
     * events it parses the claude stream-json payload and streams text via
     * [onChunk]. When token-level deltas are present (partial-messages mode)
     * they are the source of truth and the whole `assistant` message is
     * suppressed to avoid double-counting; otherwise the whole message is used.
     * The `result` event is authoritative for the final return value.
     */
    private class SseChatAccumulator(
        private val onChunk: (String) -> Unit,
    ) {
        private val answer = StringBuilder()
        private var resultOverride: String? = null
        private var currentEvent: String? = null
        private var sawDelta = false

        fun consume(line: String) {
            when {
                line.startsWith(SSE_EVENT_PREFIX) -> currentEvent = line.removePrefix(SSE_EVENT_PREFIX).trim()
                line.startsWith(SSE_DATA_PREFIX) -> handleData(line.removePrefix(SSE_DATA_PREFIX))
                line.isBlank() -> currentEvent = null
            }
        }

        private fun handleData(data: String) {
            if (currentEvent != SSE_EVENT_LINE) return
            val event = parseStreamJsonLine(data)
            event.delta?.let {
                sawDelta = true
                emit(it)
            }
            event.message?.let { if (!sawDelta) emit(it) }
            event.result?.let { resultOverride = it }
        }

        private fun emit(text: String) {
            answer.append(text)
            onChunk(text)
        }

        fun answer(): String = resultOverride ?: answer.toString()
    }
}

private const val SSE_EVENT_PREFIX = "event:"
private const val SSE_DATA_PREFIX = "data:"
private const val SSE_EVENT_LINE = "line"

/**
 * One parsed claude stream-json line.
 * - [delta]: an incremental token (partial-messages `content_block_delta`).
 * - [message]: a whole `assistant` message (non-partial mode).
 * - [result]: the authoritative final answer (`result/success`).
 */
internal data class StreamJsonEvent(
    val delta: String? = null,
    val message: String? = null,
    val result: String? = null,
)

/**
 * Parses one NDJSON line from the claude stream-json format. Recognises
 * token deltas, whole assistant messages, and the result event. Any malformed
 * or unrecognised line yields an empty [StreamJsonEvent]. Never throws.
 */
internal fun parseStreamJsonLine(
    line: String,
    objectMapper: ObjectMapper = jacksonObjectMapper(),
): StreamJsonEvent {
    if (line.isBlank()) return StreamJsonEvent()
    return runCatching {
        val parsed = objectMapper.readTree(line)
        // Tolerate a JSON-string-wrapped object (defensive against any SSE layer
        // that re-encodes the already-JSON line as a quoted string).
        val tree = if (parsed.isTextual) objectMapper.readTree(parsed.asText()) else parsed
        when (tree.path("type").asText(null)) {
            "assistant" -> StreamJsonEvent(message = extractAssistantText(tree))
            "result" -> StreamJsonEvent(result = extractResultText(tree))
            "stream_event" -> StreamJsonEvent(delta = extractDeltaText(tree))
            else -> StreamJsonEvent()
        }
    }.getOrElse { StreamJsonEvent() }
}

private fun extractDeltaText(tree: JsonNode): String? {
    val event = tree.path("event")
    if (event.path("type").asText(null) != "content_block_delta") return null
    val delta = event.path("delta")
    if (delta.path("type").asText(null) != "text_delta") return null
    return delta.path("text").asText(null)?.takeIf { it.isNotEmpty() }
}

private fun extractAssistantText(tree: JsonNode): String? {
    val content = tree.path("message").path("content")
    if (!content.isArray) return null
    return content
        .filter { it.path("type").asText(null) == "text" }
        .mapNotNull { node -> node.path("text").asText(null)?.takeIf { it.isNotEmpty() } }
        .joinToString("")
        .takeIf { it.isNotEmpty() }
}

private fun extractResultText(tree: JsonNode): String? {
    if (tree.path("subtype").asText(null) != "success") return null
    return tree.path("result").asText(null)?.takeIf { it.isNotEmpty() }
}

package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.agents.config.ChatGenerationProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
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
 * agent job on the runner Pod.
 *
 * Flow:
 * 1. Resolve the target workspace via [ChatGenerationProperties.runnerPodWorkspaceId].
 * 2. POST `{gatewayEndpoint}/agents/headless` with `kind` + `prompt`; read
 *    back the job `id`.
 * 3. GET `{gatewayEndpoint}/agents/headless/{id}/stream` as text/event-stream;
 *    parse Spring SSE format (event:/data: pairs) and delegate each line to
 *    [parseStreamJsonLine] to extract assistant text chunks.
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
    private val restClient: RestClient,
    private val workspaces: WorkspaceRepository,
    private val props: ChatGenerationProperties,
) : ChatGenerationPort {
    private val log = LoggerFactory.getLogger(RunnerPodChatGenerator::class.java)

    override fun generate(
        prompt: String,
        onChunk: (String) -> Unit,
    ): String {
        val gatewayEndpoint = resolveGatewayEndpoint() ?: return ""
        val jobId = startHeadlessJob(gatewayEndpoint, prompt) ?: return ""
        return streamJobOutput(gatewayEndpoint, jobId, onChunk)
    }

    private fun resolveGatewayEndpoint(): String? {
        val rawId = props.runnerPodWorkspaceId
        if (rawId.isNullOrBlank()) {
            log.warn("chat.generation.runner-pod-workspace-id is not configured — no answer produced")
            return null
        }
        var endpoint: String? = null
        runCatching {
            val workspaceId = WorkspaceId(UUID.fromString(rawId))
            val workspace = workspaces.findById(workspaceId)
            if (workspace == null) {
                log.warn("RunnerPodChatGenerator: workspace {} not found — no answer produced", rawId)
                return@runCatching
            }
            val ep = workspace.gatewayEndpoint
            if (ep.isNullOrBlank()) {
                log.warn(
                    "RunnerPodChatGenerator: workspace {} has no gateway endpoint — no answer produced",
                    rawId,
                )
                return@runCatching
            }
            endpoint = ep
        }.onFailure { ex ->
            log.warn("RunnerPodChatGenerator: failed to resolve workspace {}: {}", rawId, ex.message)
        }
        return endpoint
    }

    private fun startHeadlessJob(
        gatewayEndpoint: String,
        prompt: String,
    ): String? {
        var jobId: String? = null
        runCatching {
            val response =
                restClient
                    .post()
                    .uri("$gatewayEndpoint/agents/headless")
                    .body(
                        mapOf(
                            "kind" to props.runnerPodAgentKind,
                            "prompt" to prompt,
                        ),
                    ).retrieve()
                    .body(Map::class.java)
            @Suppress("UNCHECKED_CAST")
            jobId = (response as? Map<String, Any?>)?.get("id")?.toString()
            if (jobId.isNullOrBlank()) {
                log.warn("RunnerPodChatGenerator: gateway returned no job id")
                jobId = null
            }
        }.onFailure { ex ->
            log.warn("RunnerPodChatGenerator: failed to start headless job: {}", ex.message)
        }
        return jobId
    }

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
     * events it parses the claude stream-json payload, streams assistant text
     * via [onChunk], and tracks the authoritative `result`. Other events and
     * blank separators are ignored.
     */
    private class SseChatAccumulator(
        private val onChunk: (String) -> Unit,
    ) {
        private val answer = StringBuilder()
        private var resultOverride: String? = null
        private var currentEvent: String? = null

        fun consume(line: String) {
            when {
                line.startsWith(SSE_EVENT_PREFIX) -> currentEvent = line.removePrefix(SSE_EVENT_PREFIX).trim()
                line.startsWith(SSE_DATA_PREFIX) -> handleData(line.removePrefix(SSE_DATA_PREFIX))
                line.isBlank() -> currentEvent = null
            }
        }

        private fun handleData(data: String) {
            if (currentEvent != SSE_EVENT_LINE) return
            val (text, result) = parseStreamJsonLine(data)
            text?.let {
                answer.append(it)
                onChunk(it)
            }
            result?.let { resultOverride = it }
        }

        fun answer(): String = resultOverride ?: answer.toString()
    }
}

private const val SSE_EVENT_PREFIX = "event:"
private const val SSE_DATA_PREFIX = "data:"
private const val SSE_EVENT_LINE = "line"

/**
 * Parses one NDJSON line from the claude stream-json format.
 *
 * Returns a pair of:
 * - extracted assistant text chunk (non-null when the line is an
 *   `assistant` message event with text content), and
 * - a result override string (non-null when the line is a
 *   `result/success` event).
 *
 * Any malformed or unrecognised line returns `(null, null)`. Never throws.
 */
internal fun parseStreamJsonLine(
    line: String,
    objectMapper: ObjectMapper = jacksonObjectMapper(),
): Pair<String?, String?> {
    if (line.isBlank()) return null to null
    return runCatching {
        val parsed = objectMapper.readTree(line)
        // Tolerate a JSON-string-wrapped object (defensive against any SSE layer
        // that re-encodes the already-JSON line as a quoted string).
        val tree = if (parsed.isTextual) objectMapper.readTree(parsed.asText()) else parsed
        when (tree.path("type").asText(null)) {
            "assistant" -> extractAssistantText(tree) to null
            "result" -> null to extractResultText(tree)
            else -> null to null
        }
    }.getOrElse { null to null }
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

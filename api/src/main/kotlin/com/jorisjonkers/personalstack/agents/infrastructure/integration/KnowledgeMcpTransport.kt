package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.agents.config.RagProperties
import com.jorisjonkers.personalstack.agents.domain.port.RetrievalPort
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-level JSON-RPC transport against knowledge-api's /mcp endpoint.
 * The server speaks the MCP 2024-11 dialect; this component owns the
 * wire format, auth header, and response parsing — no business logic.
 *
 * Auth: bearer-token allow-list (see knowledge-api/vault-secrets);
 * each calling service has its own token, scoped by Vault path.
 *
 * Callers: KnowledgeRecallClient, KnowledgeWriteClient.
 */
@Component
class KnowledgeMcpTransport(
    private val restClient: RestClient,
    private val props: RagProperties,
) {
    private val log = LoggerFactory.getLogger(KnowledgeMcpTransport::class.java)
    private val mapper = jacksonObjectMapper()
    private val seq = AtomicLong(0)

    fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): JsonNode? {
        val payload =
            mapOf(
                "jsonrpc" to "2.0",
                "id" to seq.incrementAndGet(),
                "method" to "tools/call",
                "params" to mapOf("name" to name, "arguments" to arguments),
            )
        return restClient
            .post()
            .uri("${props.knowledgeMcpUrl}/mcp")
            .headers { h ->
                h[HttpHeaders.CONTENT_TYPE] = "application/json"
                if (props.knowledgeMcpToken.isNotBlank()) {
                    h[HttpHeaders.AUTHORIZATION] = "Bearer ${props.knowledgeMcpToken}"
                }
            }.body(payload)
            .retrieve()
            .body(String::class.java)
            ?.let(mapper::readTree)
    }

    fun parseStructuredHits(hitsNode: JsonNode): List<RetrievalPort.Snippet> =
        hitsNode.mapNotNull { hit ->
            val id = hit.get("id")?.asText()?.takeIf { it.isNotBlank() }
            val title = hit.get("title")?.asText().orEmpty()
            val snippet = hit.get("snippet")?.asText().orEmpty()
            val score = hit.get("score")?.asDouble() ?: 0.0
            val scope = hit.get("scope")?.asText().orEmpty()
            if (title.isBlank() && snippet.isBlank()) return@mapNotNull null
            RetrievalPort.Snippet(
                source = "kb:$scope:$title".trimEnd(':'),
                text = snippet.ifEmpty { title },
                score = score,
                id = id,
            )
        }

    fun parseRecallHits(text: String): List<RetrievalPort.Snippet> {
        // The recall tool's text form is one hit per blank-line-separated block:
        // "title\nsnippet\n(score=0.42)". Keep parsing tolerant — server
        // formatting churn shouldn't break agent context.
        return text.split(Regex("\\n\\n+")).mapNotNull { chunk ->
            val lines = chunk.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@mapNotNull null
            val score = scoreFromTrailer(lines.last())
            val title = lines.first()
            val body = lines.drop(1).filterNot { it.startsWith("(score=") }.joinToString("\n")
            RetrievalPort.Snippet(source = "kb:$title", text = body.ifEmpty { title }, score = score)
        }
    }

    fun scoreFromTrailer(line: String): Double {
        val m = Regex("score=([0-9.]+)").find(line) ?: return 0.0
        return m.groupValues[1].toDoubleOrNull() ?: 0.0
    }

    fun recall(
        query: String,
        limit: Int,
        scope: String? = null,
    ): List<RetrievalPort.Snippet> =
        runCatching {
            val args =
                buildMap<String, Any?> {
                    put("query", query)
                    put("limit", limit)
                    put("mode", props.recallMode)
                    if (!scope.isNullOrBlank()) put("scope", scope)
                }
            val resp =
                callTool(
                    RECALL_TOOL,
                    args,
                )
            val result = resp?.get("result") ?: return@runCatching emptyList()
            // Prefer structuredContent.hits (MCP 2025-06) for typed parsing;
            // fall back to text block for compatibility with older server.
            val structuredHits = result.get("structuredContent")?.get("hits")
            if (structuredHits != null && structuredHits.isArray) {
                parseStructuredHits(structuredHits).take(limit)
            } else {
                val text =
                    result
                        .get("content")
                        ?.firstOrNull()
                        ?.get("text")
                        ?.asText()
                        .orEmpty()
                if (text.isBlank()) emptyList() else parseRecallHits(text).take(limit)
            }
        }.getOrElse {
            log.warn("knowledge.recall failed: {}", it.message)
            emptyList()
        }

    companion object {
        const val RECALL_TOOL = "knowledge.recall"
        const val CAPTURE_LESSON_TOOL = "knowledge.capture_lesson"
    }
}

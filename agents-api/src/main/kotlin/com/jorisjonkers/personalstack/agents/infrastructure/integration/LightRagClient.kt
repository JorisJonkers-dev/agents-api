package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.config.RagProperties
import com.jorisjonkers.personalstack.agents.domain.port.RetrievalPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * LightRAG /query in mix mode. The response shape is
 * `{ "response": "..." }` — LightRAG renders a single fused answer
 * rather than a list of snippets, so we wrap the whole answer as
 * one Snippet. The KB recall adapter complements this by returning
 * the per-note hits, which keeps the "raw snippets" channel useful
 * for the agent to expand on a specific source.
 */
@Component
class LightRagClient(
    private val restClient: RestClient,
    private val props: RagProperties,
    @param:Qualifier("agentsApiObjectMapper")
    private val objectMapper: ObjectMapper,
) : RetrievalPort {
    private val log = LoggerFactory.getLogger(LightRagClient::class.java)

    /**
     * `top_k` snake-case stays on the wire because that's what
     * LightRAG's HTTP API expects; the Kotlin field uses camelCase
     * and Jackson rewrites it via @JsonProperty.
     */
    private data class Req(
        val query: String,
        val mode: String = "mix",
        @param:JsonProperty("top_k") val topK: Int = DEFAULT_TOP_K,
    )

    private data class StreamReq(
        val query: String,
        val mode: String = "mix",
        @param:JsonProperty("top_k") val topK: Int = DEFAULT_TOP_K,
        val stream: Boolean = true,
    )

    private data class Resp(
        val response: String?,
    )

    override fun retrieve(
        query: String,
        limit: Int,
    ): List<RetrievalPort.Snippet> {
        if (!props.enabled) return emptyList()
        return runCatching {
            val body =
                restClient
                    .post()
                    .uri("${props.lightragUrl}/query")
                    .body(Req(query = query, topK = limit))
                    .retrieve()
                    .body(Resp::class.java)
            val text = body?.response ?: ""
            if (text.isBlank()) emptyList() else listOf(RetrievalPort.Snippet("lightrag", text, 1.0))
        }.getOrElse {
            log.warn("LightRAG query failed: {}", it.message)
            emptyList()
        }
    }

    fun streamQuery(
        query: String,
        onChunk: (String) -> Unit,
    ): String {
        val streamed = streamFromLightRag(query, onChunk)
        if (streamed.isNotBlank()) return streamed

        val fallback =
            retrieve(query, props.maxSnippets)
                .firstOrNull()
                ?.text
                ?: ""
        if (fallback.isNotBlank()) onChunk(fallback)
        return fallback
    }

    private fun streamFromLightRag(
        query: String,
        onChunk: (String) -> Unit,
    ): String {
        val answer = StringBuilder()
        runCatching {
            if (!props.enabled) return@runCatching ""
            restClient
                .post()
                .uri("${props.lightragUrl}/query/stream")
                .accept(MediaType.parseMediaType("application/x-ndjson"), MediaType.APPLICATION_JSON)
                .body(StreamReq(query = query, topK = props.maxSnippets))
                .exchange { _, response ->
                    check(response.statusCode.is2xxSuccessful) {
                        "LightRAG stream returned ${response.statusCode}"
                    }
                    response.body.bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.forEachLine { line ->
                            appendStreamPiece(line, answer, onChunk)
                        }
                    }
                }
            answer.toString()
        }.onFailure {
            log.warn("LightRAG stream failed: {}", it.message)
        }
        return answer.toString()
    }

    private fun appendStreamPiece(
        line: String,
        answer: StringBuilder,
        onChunk: (String) -> Unit,
    ) {
        if (line.isBlank()) return
        val piece = objectMapper.readTree(line).path("response").asText(null)
        if (piece.isNullOrEmpty()) return
        answer.append(piece)
        onChunk(piece)
    }

    companion object {
        private const val DEFAULT_TOP_K = 5
    }
}

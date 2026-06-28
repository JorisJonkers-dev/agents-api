package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.RagProperties
import com.jorisjonkers.personalstack.agents.domain.port.KnowledgeWritePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The single KnowledgeWritePort bean. Owns two MCP tool calls:
 *
 *   - `knowledge.capture_lesson` — writes a note to the KB.
 *   - dedup recall — calls the transport's recall path (limit=1) to
 *     detect near-duplicates before spending the write budget.
 *
 * findDuplicateEvidence intentionally uses the raw transport recall
 * (not KnowledgeRecallClient) so it remains functional even when the
 * retrieval flag is off — dedup is a write-side concern, not retrieval.
 */
@Component
class KnowledgeWriteClient(
    private val transport: KnowledgeMcpTransport,
    private val props: RagProperties,
) : KnowledgeWritePort {
    private val log = LoggerFactory.getLogger(KnowledgeWriteClient::class.java)

    override fun ingestNote(request: KnowledgeWritePort.CaptureRequest) {
        if (!props.captureEnabled) return
        runCatching {
            val args =
                mutableMapOf<String, Any?>(
                    "title" to request.title,
                    "body" to request.body,
                    "scope" to request.scope,
                    "tags" to request.tags,
                )
            request.source?.let { args["source"] = it }
            request.sessionId?.let { args["session_id"] = it }
            request.confidence?.let { args["confidence"] = it }
            transport.callTool(KnowledgeMcpTransport.CAPTURE_LESSON_TOOL, args)
        }.onFailure { log.warn("knowledge.capture failed: {}", it.message) }
    }

    override fun findDuplicateEvidence(
        query: String,
        minScore: Double,
    ): KnowledgeWritePort.DuplicateEvidence? =
        // Dedup recall is write-side: bypasses retrievalEnabled so duplicate
        // suppression stays active even when retrieval is toggled off.
        transport
            .recall(query, limit = 1)
            .firstOrNull { it.score >= minScore }
            ?.let { hit ->
                KnowledgeWritePort.DuplicateEvidence(
                    id = hit.id,
                    source = hit.source,
                    score = hit.score,
                )
            }
}

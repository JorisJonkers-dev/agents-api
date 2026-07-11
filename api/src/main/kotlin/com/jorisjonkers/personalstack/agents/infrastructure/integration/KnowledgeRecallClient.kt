package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.RagProperties
import com.jorisjonkers.personalstack.agents.domain.port.RetrievalPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * RetrievalPort backed by knowledge-api's MCP JSON-RPC recall tool.
 * Returns per-note scored snippets; complements LightRagClient which
 * returns a single fused answer at score 1.0.
 *
 * Disabled via `rag.sources.knowledge.enabled=false`; default true.
 * Also gated by the deprecated `rag.enabled` master toggle and the
 * `rag.retrieval.enabled` flag — both are checked at call time via
 * `RagProperties.retrievalEnabled`.
 */
@Component
@ConditionalOnProperty(
    prefix = "rag.sources.knowledge",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class KnowledgeRecallClient(
    private val transport: KnowledgeMcpTransport,
    private val props: RagProperties,
) : RetrievalPort {
    override fun retrieve(
        query: String,
        limit: Int,
        scope: String?,
    ): List<RetrievalPort.Snippet> {
        if (!props.retrievalEnabled) return emptyList()
        return transport.recall(query, limit, scope)
    }
}

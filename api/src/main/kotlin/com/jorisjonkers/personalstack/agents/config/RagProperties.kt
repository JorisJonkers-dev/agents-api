package com.jorisjonkers.personalstack.agents.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rag")
data class RagProperties(
    /**
     * @deprecated Master on/off toggle kept for one release as a rollback path.
     * Use `rag.retrieval.enabled` instead.
     * Will be removed in the release after 015-memory-seam-decoupling ships.
     */
    val enabled: Boolean = true,
    // Gate for retrieval (relaxed-binding maps `rag.retrieval.enabled`).
    val retrieval: RetrievalFlags = RetrievalFlags(),
    // Sensible cluster-default URLs make the integration-test boot
    // path work without needing every test class to wire dynamic
    // properties for the RAG feature. Production application.yml
    // overrides these via env vars.
    val lightragUrl: String = "http://lightrag.knowledge-system.svc.cluster.local:9621",
    val maxSnippets: Int = 5,
    val maxContextChars: Int = 4_000,
    // Hits below this score are dropped before injection. Keeps the
    // context envelope from growing on marginal matches.
    val minScore: Double = 0.3,
) {
    // Combines the deprecated master toggle with the retrieval flag so
    // callers do not repeat the AND logic.
    val retrievalEnabled: Boolean get() = enabled && retrieval.enabled

    data class RetrievalFlags(
        val enabled: Boolean = true,
    )
}

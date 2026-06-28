package com.jorisjonkers.personalstack.agents.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rag")
data class RagProperties(
    /**
     * @deprecated Master on/off toggle kept for one release as a rollback path.
     * Use `rag.retrieval.enabled` and `rag.capture.enabled` for independent control.
     * Will be removed in the release after 015-memory-seam-decoupling ships.
     */
    val enabled: Boolean = true,
    // Independent gates for retrieval and capture (relaxed-binding maps
    // `rag.retrieval.enabled` / `rag.capture.enabled`). Both default true
    // so the change is invisible in the all-enabled configuration.
    val retrieval: RetrievalFlags = RetrievalFlags(),
    val capture: CaptureFlags = CaptureFlags(),
    // Sensible cluster-default URLs make the integration-test boot
    // path work without needing every test class to wire dynamic
    // properties for the RAG feature. Production application.yml
    // overrides these via env vars.
    val knowledgeMcpUrl: String = "http://knowledge-api.knowledge-system.svc.cluster.local:8080",
    val knowledgeMcpToken: String = "",
    val lightragUrl: String = "http://lightrag.knowledge-system.svc.cluster.local:9621",
    val maxSnippets: Int = 5,
    val maxContextChars: Int = 4_000,
    // Hits below this score are dropped before injection. Keeps the
    // context envelope from growing on marginal matches.
    val minScore: Double = 0.3,
    // Passed to knowledge.recall as the `mode` parameter. `deep` runs
    // hybrid retrieval + listwise reranker on the knowledge-api side.
    val recallMode: String = "deep",
    // Agents-ui auto-capture uses the same policy shape as CLI
    // digest hooks: bounded writes, duplicate suppression, explicit
    // confidence, and review routing for weak candidates.
    val autoCaptureSessionCapacity: Int = 3,
    val autoCaptureBucketRefillMinutes: Long = 15,
    val autoCaptureDedupeScore: Double = 0.86,
    val autoCaptureScopedMinConfidence: Double = 0.55,
) {
    // Convenience accessors combining deprecated master toggle with the
    // fine-grained flags so callers do not repeat the AND logic.
    val retrievalEnabled: Boolean get() = enabled && retrieval.enabled
    val captureEnabled: Boolean get() = enabled && capture.enabled

    data class RetrievalFlags(
        val enabled: Boolean = true,
    )

    data class CaptureFlags(
        val enabled: Boolean = true,
    )
}

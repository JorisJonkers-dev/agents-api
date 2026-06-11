package com.jorisjonkers.personalstack.agents.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rag")
data class RagProperties(
    val enabled: Boolean = true,
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
)

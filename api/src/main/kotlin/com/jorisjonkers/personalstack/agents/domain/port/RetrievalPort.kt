package com.jorisjonkers.personalstack.agents.domain.port

/**
 * Driven port: "give me snippets relevant to this prompt." The
 * application doesn't care whether they came from LightRAG, FTS, or
 * a static vector store — only the final list is exposed. Adapters
 * fan out to the underlying sources and rerank as needed.
 */
interface RetrievalPort {
    data class Snippet(
        val source: String,
        val text: String,
        val score: Double,
        val id: String? = null,
    )

    fun retrieve(
        query: String,
        limit: Int,
    ): List<Snippet>
}

/**
 * Driven port for capturing what an agent learned. Auto-capture
 * sends explicit provenance, confidence, and duplicate-check context;
 * manual callers can still use the simple convenience overload.
 */
interface KnowledgeWritePort {
    data class CaptureRequest(
        val title: String,
        val body: String,
        val scope: String,
        val tags: List<String> = emptyList(),
        val source: String? = null,
        val sessionId: String? = null,
        val confidence: Double? = null,
    )

    data class DuplicateEvidence(
        val id: String?,
        val source: String,
        val score: Double,
    )

    fun ingestNote(
        title: String,
        body: String,
        scope: String,
        tags: List<String> = emptyList(),
    ) = ingestNote(CaptureRequest(title = title, body = body, scope = scope, tags = tags))

    fun ingestNote(request: CaptureRequest)

    fun findDuplicateEvidence(
        query: String,
        minScore: Double,
    ): DuplicateEvidence? = null
}

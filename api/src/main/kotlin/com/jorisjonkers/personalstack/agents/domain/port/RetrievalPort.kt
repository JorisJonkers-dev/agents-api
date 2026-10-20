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
        scope: String? = null,
    ): List<Snippet>
}

package com.jorisjonkers.personalstack.agents.application.rag

import com.jorisjonkers.personalstack.agents.config.RagProperties
import com.jorisjonkers.personalstack.agents.domain.port.RetrievalPort
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Aggregates retrieval results from every RetrievalPort bean,
 * dedupes by note id (when present) then by text, filters below the
 * score floor, ranks by score, and formats into a single
 * `<context>...</context>` envelope that SendUserInputCommandHandler
 * prepends to the agent's input.
 *
 * No envelope is emitted when no hit clears the score floor or when
 * the character budget is exhausted before the first chunk lands.
 *
 * Why a single envelope rather than per-source headers: every CLI
 * we plug in has its own quirks for parsing user input. A single
 * fenced region with consistent markers is the cheapest "ignore if
 * you don't understand it" surface.
 */
@Component
class ContextBuilder(
    private val sources: List<RetrievalPort>,
    private val props: RagProperties,
    registry: MeterRegistry,
) {
    private val injectedHits = registry.counter("rag.hits.injected")
    private val injectedChars = registry.counter("rag.chars.injected")

    fun augment(userPrompt: String): String {
        if (!props.enabled || sources.isEmpty()) return userPrompt
        val chunks = buildChunks(dedupedAndFiltered(userPrompt))
        // Empty when all snippets failed the score floor, the merged list was
        // empty, or the character budget was too tight for even the first chunk.
        if (chunks.isEmpty()) return userPrompt
        val usedChars = chunks.sumOf { it.length }
        injectedHits.increment(chunks.size.toDouble())
        injectedChars.increment(usedChars.toDouble())
        return buildEnvelope(chunks) + userPrompt
    }

    private fun buildChunks(merged: List<RetrievalPort.Snippet>): List<String> {
        var usedChars = 0
        val result = mutableListOf<String>()
        for (s in merged) {
            val chunk = "[${s.source}] ${s.text.take(800)}\n"
            if (usedChars + chunk.length > props.maxContextChars) break
            result += chunk
            usedChars += chunk.length
        }
        return result
    }

    private fun buildEnvelope(chunks: List<String>): String =
        buildString {
            append("<context source=\"agents-rag\">\n")
            chunks.forEach { append(it) }
            append("</context>\n\n")
        }

    private fun dedupedAndFiltered(query: String): List<RetrievalPort.Snippet> {
        val seenIds = mutableSetOf<String>()
        val seenTexts = mutableSetOf<String>()
        return sources
            .flatMap { it.retrieve(query, props.maxSnippets) }
            .filter { it.score >= props.minScore }
            .sortedByDescending { it.score }
            .filter { s ->
                if (s.id != null) seenIds.add(s.id) else seenTexts.add(s.text.trim())
            }.take(props.maxSnippets)
    }
}

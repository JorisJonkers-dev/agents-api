package com.jorisjonkers.personalstack.agents.application.rag

import com.jorisjonkers.personalstack.agents.config.RagProperties
import com.jorisjonkers.personalstack.agents.domain.port.RetrievalPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContextBuilderTest {
    private val registry = SimpleMeterRegistry()

    private fun props(
        enabled: Boolean = true,
        retrievalEnabled: Boolean = true,
        captureEnabled: Boolean = true,
        maxSnippets: Int = 5,
        maxContextChars: Int = 4_000,
        minScore: Double = 0.0,
        recallMode: String = "deep",
    ): RagProperties =
        RagProperties(
            enabled = enabled,
            retrieval = RagProperties.RetrievalFlags(enabled = retrievalEnabled),
            capture = RagProperties.CaptureFlags(enabled = captureEnabled),
            knowledgeMcpUrl = "http://kb:8080",
            knowledgeMcpToken = "",
            lightragUrl = "http://lightrag:9621",
            maxSnippets = maxSnippets,
            maxContextChars = maxContextChars,
            minScore = minScore,
            recallMode = recallMode,
        )

    private class FakeSource(
        private val snippets: List<RetrievalPort.Snippet>,
    ) : RetrievalPort {
        override fun retrieve(
            query: String,
            limit: Int,
        ): List<RetrievalPort.Snippet> = snippets
    }

    @Test
    fun `augment returns plain prompt when RAG disabled`() {
        val builder = ContextBuilder(emptyList(), props(enabled = false), registry)
        assertThat(builder.augment("hi")).isEqualTo("hi")
    }

    @Test
    fun `augment returns plain prompt when no sources hit`() {
        val builder = ContextBuilder(listOf(FakeSource(emptyList())), props(), registry)
        assertThat(builder.augment("hi")).isEqualTo("hi")
    }

    @Test
    fun `augment wraps merged sorted snippets in a context envelope`() {
        val builder =
            ContextBuilder(
                listOf(
                    FakeSource(listOf(RetrievalPort.Snippet("kb:a", "first", 0.9))),
                    FakeSource(listOf(RetrievalPort.Snippet("lightrag", "second", 0.5))),
                ),
                props(),
                registry,
            )
        val out = builder.augment("question")
        assertThat(out).startsWith("<context source=\"agents-rag\">")
        assertThat(out).contains("[kb:a] first")
        assertThat(out).contains("[lightrag] second")
        assertThat(out).contains("question")
        // Higher-score snippet comes first
        assertThat(out.indexOf("first")).isLessThan(out.indexOf("second"))
    }

    @Test
    fun `augment dedupes snippets with identical text`() {
        val builder =
            ContextBuilder(
                listOf(
                    FakeSource(listOf(RetrievalPort.Snippet("kb:a", "same", 0.9))),
                    FakeSource(listOf(RetrievalPort.Snippet("kb:b", "same", 0.8))),
                ),
                props(),
                registry,
            )
        val out = builder.augment("q")
        val count = out.split("same").size - 1
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `augment dedupes snippets with the same id regardless of text`() {
        val builder =
            ContextBuilder(
                listOf(
                    FakeSource(listOf(RetrievalPort.Snippet("kb:a", "text-a", 0.9, id = "note-1"))),
                    FakeSource(listOf(RetrievalPort.Snippet("kb:b", "text-b", 0.8, id = "note-1"))),
                ),
                props(),
                registry,
            )
        val out = builder.augment("q")
        // Only the first occurrence (higher score) should appear.
        assertThat(out).contains("text-a")
        assertThat(out).doesNotContain("text-b")
    }

    @Test
    fun `augment drops snippets below the score floor`() {
        val builder =
            ContextBuilder(
                listOf(
                    FakeSource(
                        listOf(
                            RetrievalPort.Snippet("kb:a", "above", 0.9),
                            RetrievalPort.Snippet("kb:b", "below", 0.1),
                        ),
                    ),
                ),
                props(minScore = 0.3),
                registry,
            )
        val out = builder.augment("q")
        assertThat(out).contains("above")
        assertThat(out).doesNotContain("below")
    }

    @Test
    fun `augment returns plain prompt when all snippets are below the score floor`() {
        val builder =
            ContextBuilder(
                listOf(
                    FakeSource(listOf(RetrievalPort.Snippet("kb:a", "low", 0.1))),
                ),
                props(minScore = 0.5),
                registry,
            )
        assertThat(builder.augment("q")).isEqualTo("q")
    }

    @Test
    fun `augment respects maxContextChars`() {
        val long = "x".repeat(900)
        val builder =
            ContextBuilder(
                listOf(
                    FakeSource(
                        (1..10).map { RetrievalPort.Snippet("kb:$it", long, 1.0 - it * 0.01) },
                    ),
                ),
                props(maxSnippets = 10, maxContextChars = 1_500),
                registry,
            )
        val out = builder.augment("q")
        // At most one full snippet fits before the budget runs out (~900 chars per chunk).
        val count = out.split("[kb:").size - 1
        assertThat(count).isLessThanOrEqualTo(2)
    }

    @Test
    fun `augment increments hit and char counters`() {
        val builder =
            ContextBuilder(
                listOf(
                    FakeSource(listOf(RetrievalPort.Snippet("kb:a", "hello", 0.9))),
                ),
                props(),
                registry,
            )
        builder.augment("q")
        assertThat(registry.counter("rag.hits.injected").count()).isEqualTo(1.0)
        assertThat(registry.counter("rag.chars.injected").count()).isGreaterThan(0.0)
    }

    // --- new tests for flag-split and per-source conditional ---

    @Test
    fun `augment returns plain prompt when retrieval flag is off regardless of capture flag`() {
        val source = FakeSource(listOf(RetrievalPort.Snippet("kb:a", "should not appear", 0.9)))
        val builder = ContextBuilder(listOf(source), props(retrievalEnabled = false), registry)
        assertThat(builder.augment("q")).isEqualTo("q")
    }

    @Test
    fun `augment injects context when retrieval is on even if capture flag is off`() {
        val source = FakeSource(listOf(RetrievalPort.Snippet("kb:a", "snippet text", 0.9)))
        val builder = ContextBuilder(listOf(source), props(captureEnabled = false), registry)
        val out = builder.augment("q")
        assertThat(out).contains("snippet text")
    }

    @Test
    fun `augment returns plain prompt when sources list is empty (genuine NoOp)`() {
        val builder = ContextBuilder(emptyList(), props(), registry)
        assertThat(builder.augment("q")).isEqualTo("q")
    }

    @Test
    fun `augment with score-1-0 source and genuinely-scored source both present`() {
        // score-1.0 (LightRAG-style blob) sorts first by the existing score-desc rule.
        // The genuinely-scored snippet still appears — score floor 0.3 is cleared.
        val builder =
            ContextBuilder(
                listOf(
                    FakeSource(listOf(RetrievalPort.Snippet("lightrag", "fused answer", 1.0))),
                    FakeSource(listOf(RetrievalPort.Snippet("kb:a", "precise snippet", 0.82))),
                ),
                props(minScore = 0.3),
                registry,
            )
        val out = builder.augment("q")
        assertThat(out).contains("fused answer")
        assertThat(out).contains("precise snippet")
        // score-1.0 source sorts first (existing default ordering preserved)
        assertThat(out.indexOf("fused answer")).isLessThan(out.indexOf("precise snippet"))
    }

    @Test
    fun `augment with disabled source absent from merge`() {
        // Simulate a source being disabled via @ConditionalOnProperty by simply not
        // including it in the injected sources list — the conditional removes the bean
        // entirely so ContextBuilder never sees it.
        val onlySource = FakeSource(listOf(RetrievalPort.Snippet("kb:a", "only this", 0.9)))
        val builder = ContextBuilder(listOf(onlySource), props(), registry)
        val out = builder.augment("q")
        assertThat(out).contains("only this")
        assertThat(out).doesNotContain("lightrag")
    }
}

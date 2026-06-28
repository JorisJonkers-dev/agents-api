package com.jorisjonkers.personalstack.agents.domain.port

/**
 * Driven port: the generation backend for chat. Today backed by
 * LightRAG; later replaceable with a runner-Pod streamer without
 * touching the application layer.
 *
 * [prompt] is the full prompt to generate from (history + user turn).
 * [onChunk] is called for each incremental token/piece as it arrives.
 * Returns the full concatenated answer.
 */
interface ChatGenerationPort {
    fun generate(
        prompt: String,
        onChunk: (String) -> Unit,
    ): String
}

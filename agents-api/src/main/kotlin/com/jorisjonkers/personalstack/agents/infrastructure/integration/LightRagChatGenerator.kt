package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.domain.port.ChatGenerationPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * [ChatGenerationPort] adapter that delegates to [LightRagClient.streamQuery].
 * Preserves LightRAG mix-mode grounding for chat until the runner-Pod
 * generation path is live and verified (FR-006).
 *
 * Active only when [LightRagClient] is active (same property guard).
 */
@Component
@ConditionalOnProperty(
    prefix = "rag.sources.lightrag",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class LightRagChatGenerator(
    private val lightRag: LightRagClient,
) : ChatGenerationPort {
    override fun generate(
        prompt: String,
        onChunk: (String) -> Unit,
    ): String = lightRag.streamQuery(prompt, onChunk)
}

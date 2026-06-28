package com.jorisjonkers.personalstack.agents.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Controls which [com.jorisjonkers.personalstack.agents.domain.port.ChatGenerationPort]
 * implementation is active.
 *
 * `backend` selects the implementation:
 * - `lightrag` (default) — delegates to LightRAG via [LightRagClient].
 * - `runner-pod` — routes chat to a headless agent job on the runner Pod
 *   (via agent-gateway SSE stream).
 *
 * `runnerPodWorkspaceId` and `runnerPodAgentKind` are only consulted when
 * `backend=runner-pod`.
 */
@ConfigurationProperties(prefix = "chat.generation")
data class ChatGenerationProperties(
    val backend: String = "lightrag",
    val runnerPodWorkspaceId: String? = null,
    val runnerPodAgentKind: String = "CLAUDE",
)

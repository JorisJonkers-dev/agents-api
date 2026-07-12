package com.jorisjonkers.personalstack.agents.config

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
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
 *
 * `runnerPodEnableKbHooks` opts the chat-generation headless job into KB
 * auto-recall/capture hooks. Defaults to false: the gateway sets
 * KB_AUTO_MCP_DISABLED=1 so chat workers do not fire accidental KB writes.
 */
@ConfigurationProperties(prefix = "chat.generation")
data class ChatGenerationProperties(
    val backend: String = "lightrag",
    val runnerPodWorkspaceId: String? = null,
    val runnerPodAgentKind: WorkspaceAgentKind = WorkspaceAgentKind.CLAUDE,
    val runnerPodEnableKbHooks: Boolean = false,
)

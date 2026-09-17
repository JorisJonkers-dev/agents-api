package com.jorisjonkers.personalstack.agents.domain.model

/**
 * What a conversation is wired to talk to.
 *
 * - [PLAIN] — the no-Pod conversation surface: messages are persisted
 *   and nothing else happens server-side.
 * - [KNOWLEDGE] — a conversation that operates on the knowledge base via
 *   an agent-runner Pod calling the `knowledge.*` MCP tools. The Pod
 *   binding + streaming land in a follow-up; this value lets the
 *   redesigned UI mark a conversation as KB-mode and lets the backend
 *   route on it without a second migration later.
 */
enum class ConversationKind {
    PLAIN,
    KNOWLEDGE,
}

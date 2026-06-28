package com.jorisjonkers.personalstack.agents.domain.model

/**
 * What a chat session is wired to talk to.
 *
 * - [PLAIN] — the existing no-Pod conversation surface: messages are
 *   persisted and nothing else happens server-side.
 * - [KNOWLEDGE] — a session that operates on the knowledge base via an
 *   agent-runner Pod calling the `knowledge.*` MCP tools. The Pod
 *   binding + streaming land in a follow-up; this value lets the
 *   redesigned UI mark a session as KB-mode and lets the backend route
 *   on it without a second migration later.
 */
enum class ChatSessionKind {
    PLAIN,
    KNOWLEDGE,
}

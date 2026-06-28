package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Chat sessions are the no-Pod conversation surface. They live next
 * to [Conversation] — the older type that the legacy chat UI still
 * writes against — and exist so the redesigned UI can surface
 * "chat" / "scratch" / "workspace" as three sibling tabs without
 * coupling each tab to a Pod lifecycle. The legacy `conversations`
 * table stays for the rollout window.
 */
data class ChatSession(
    val id: ChatSessionId,
    val userId: UUID,
    val title: String?,
    val status: ChatSessionStatus,
    val kind: ChatSessionKind,
    val createdAt: Instant,
    val updatedAt: Instant,
)

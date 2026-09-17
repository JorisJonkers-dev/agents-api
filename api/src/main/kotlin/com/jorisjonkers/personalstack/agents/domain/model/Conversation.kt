package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The no-Pod chat surface: a chat between the user and an agent that
 * has no Workspace. Formerly `ChatSession`, renamed onto this name
 * once the legacy `conversation` table (a different, unrelated model)
 * was dropped.
 */
data class Conversation(
    val id: ConversationId,
    val userId: UUID,
    val title: String?,
    val status: ConversationStatus,
    val kind: ConversationKind,
    val createdAt: Instant,
    val updatedAt: Instant,
)

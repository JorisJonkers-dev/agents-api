package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant
import java.util.UUID

data class Conversation(
    val id: ConversationId,
    val userId: UUID,
    val title: String,
    val status: ConversationStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

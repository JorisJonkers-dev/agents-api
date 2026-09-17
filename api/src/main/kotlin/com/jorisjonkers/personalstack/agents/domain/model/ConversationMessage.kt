package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant

data class ConversationMessage(
    val id: ConversationMessageId,
    val conversationId: ConversationId,
    val role: ConversationMessageRole,
    val body: String,
    val createdAt: Instant,
)

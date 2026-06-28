package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant

data class Message(
    val id: MessageId,
    val conversationId: ConversationId,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant,
)

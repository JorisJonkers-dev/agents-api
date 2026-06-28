package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import java.time.Instant
import java.util.UUID

data class ConversationResponse(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(conversation: Conversation): ConversationResponse =
            ConversationResponse(
                id = conversation.id.value,
                userId = conversation.userId,
                title = conversation.title,
                status = conversation.status.name,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt,
            )
    }
}

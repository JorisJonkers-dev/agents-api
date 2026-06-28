package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.model.Message
import java.time.Instant
import java.util.UUID

data class MessageResponse(
    val id: UUID,
    val conversationId: UUID,
    val role: String,
    val content: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(message: Message): MessageResponse =
            MessageResponse(
                id = message.id.value,
                conversationId = message.conversationId.value,
                role = message.role.name,
                content = message.content,
                createdAt = message.createdAt,
            )
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageRole
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

// Wire-compat DTOs for the deprecated /api/v1/chat-sessions alias
// (ChatSessionController). Kept separate from ConversationDtos so the
// canonical shapes (ConversationMessageResponse.conversationId, the
// typed ConversationDetailResponse) can change without moving this
// surface, which agents-ui still depends on byte-for-byte.

data class StartChatSessionRequest(
    @field:Size(max = 120) val title: String? = null,
    val kind: ConversationKind? = null,
)

data class ChatSessionResponse(
    val id: UUID,
    val userId: UUID,
    val title: String?,
    val status: String,
    val kind: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(c: Conversation) =
            ChatSessionResponse(
                id = c.id.value,
                userId = c.userId,
                title = c.title,
                status = c.status.name,
                kind = c.kind.name,
                createdAt = c.createdAt,
                updatedAt = c.updatedAt,
            )
    }
}

data class AppendChatMessageRequest(
    @field:NotBlank val body: String,
    val role: ConversationMessageRole = ConversationMessageRole.USER,
)

data class ChatMessageResponse(
    val id: UUID,
    val sessionId: UUID,
    val role: String,
    val body: String,
    val createdAt: Instant,
) {
    companion object {
        fun of(m: ConversationMessage) =
            ChatMessageResponse(
                id = m.id.value,
                sessionId = m.conversationId.value,
                role = m.role.name,
                body = m.body,
                createdAt = m.createdAt,
            )
    }
}

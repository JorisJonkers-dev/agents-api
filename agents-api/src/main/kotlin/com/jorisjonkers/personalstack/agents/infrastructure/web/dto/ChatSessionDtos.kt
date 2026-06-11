package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.model.ChatMessage
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageRole
import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionKind
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class StartChatSessionRequest(
    @field:Size(max = 120) val title: String? = null,
    // Nullable so an absent field deserializes to null on any Jackson
    // setup (a non-null Kotlin default is not honored for a missing
    // property); the controller coalesces null to PLAIN.
    val kind: ChatSessionKind? = null,
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
        fun of(s: ChatSession) =
            ChatSessionResponse(
                id = s.id.value,
                userId = s.userId,
                title = s.title,
                status = s.status.name,
                kind = s.kind.name,
                createdAt = s.createdAt,
                updatedAt = s.updatedAt,
            )
    }
}

data class AppendChatMessageRequest(
    @field:NotBlank val body: String,
    val role: ChatMessageRole = ChatMessageRole.USER,
)

data class ChatMessageResponse(
    val id: UUID,
    val sessionId: UUID,
    val role: String,
    val body: String,
    val createdAt: Instant,
) {
    companion object {
        fun of(m: ChatMessage) =
            ChatMessageResponse(
                id = m.id.value,
                sessionId = m.sessionId.value,
                role = m.role.name,
                body = m.body,
                createdAt = m.createdAt,
            )
    }
}

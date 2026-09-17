package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageRole
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class StartConversationRequest(
    @field:Size(max = 120) val title: String? = null,
    // Nullable so an absent field deserializes to null on any Jackson
    // setup (a non-null Kotlin default is not honored for a missing
    // property); the controller coalesces null to PLAIN.
    val kind: ConversationKind? = null,
)

data class ConversationResponse(
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
            ConversationResponse(
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

data class AppendConversationMessageRequest(
    @field:NotBlank val body: String,
    val role: ConversationMessageRole = ConversationMessageRole.USER,
)

data class ConversationMessageResponse(
    val id: UUID,
    val conversationId: UUID,
    val role: String,
    val body: String,
    val createdAt: Instant,
) {
    companion object {
        fun of(m: ConversationMessage) =
            ConversationMessageResponse(
                id = m.id.value,
                conversationId = m.conversationId.value,
                role = m.role.name,
                body = m.body,
                createdAt = m.createdAt,
            )
    }
}

// Criterion: GET /api/v1/chat-sessions/{id} returned an untyped
// Map<String, Any> (keys "session"/"messages"), giving the generated
// TS client no real type. The canonical endpoint returns this instead;
// the deprecated alias keeps returning the untyped map for wire
// compatibility.
data class ConversationDetailResponse(
    val conversation: ConversationResponse,
    val messages: List<ConversationMessageResponse>,
)

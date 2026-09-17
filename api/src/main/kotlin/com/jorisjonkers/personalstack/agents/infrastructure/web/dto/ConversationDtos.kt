package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageRole
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

// body/content both nullable (rather than one @NotBlank field) so
// either the canonical `body` or the legacy `content` name can be
// absent without failing bean validation; resolvedBody() picks
// whichever is present and the handler still rejects a blank result.
data class AppendConversationMessageRequest(
    val body: String? = null,
    val content: String? = null,
    val role: ConversationMessageRole? = null,
) {
    fun resolvedBody(): String = (body ?: content).orEmpty()

    fun resolvedRole(): ConversationMessageRole = role ?: ConversationMessageRole.USER
}

data class ConversationMessageResponse(
    val id: UUID,
    val conversationId: UUID,
    val role: String,
    val body: String,
    // Wire-compat: the legacy /api/v1/conversations response used
    // `content`; duplicated alongside `body` so an old reader of the
    // canonical path keeps working.
    val content: String,
    val createdAt: Instant,
) {
    companion object {
        fun of(m: ConversationMessage) =
            ConversationMessageResponse(
                id = m.id.value,
                conversationId = m.conversationId.value,
                role = m.role.name,
                body = m.body,
                content = m.body,
                createdAt = m.createdAt,
            )
    }
}

// Flat shape restored to match the legacy /api/v1/conversations/{id}
// response (id/userId/title/status/createdAt/updatedAt at top level);
// only `kind` and `messages` are additions. The deprecated
// /api/v1/chat-sessions alias keeps its own untyped map instead.
data class ConversationDetailResponse(
    val id: UUID,
    val userId: UUID,
    val title: String?,
    val status: String,
    val kind: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val messages: List<ConversationMessageResponse>,
) {
    companion object {
        fun of(
            conversation: Conversation,
            messages: List<ConversationMessage>,
        ) = ConversationDetailResponse(
            id = conversation.id.value,
            userId = conversation.userId,
            title = conversation.title,
            status = conversation.status.name,
            kind = conversation.kind.name,
            createdAt = conversation.createdAt,
            updatedAt = conversation.updatedAt,
            messages = messages.map(ConversationMessageResponse::of),
        )
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.chat.ChatAnswerStreamService
import com.jorisjonkers.personalstack.agents.application.command.AppendConversationMessageCommand
import com.jorisjonkers.personalstack.agents.application.command.ArchiveConversationCommand
import com.jorisjonkers.personalstack.agents.application.command.StartConversationCommand
import com.jorisjonkers.personalstack.agents.application.query.ConversationQueryService
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageId
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AppendChatMessageRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.ChatMessageResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.ChatSessionResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.StartChatSessionRequest
import com.jorisjonkers.personalstack.common.command.CommandBus
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * Deprecated alias for [ConversationController] at the old path.
 * Serves the same Conversation model with the pre-rename shapes so
 * agents-ui keeps working unchanged; remove once agents-ui migrates to
 * /api/v1/conversations.
 */
@RestController
@RequestMapping("/api/v1/chat-sessions")
class ChatSessionController(
    private val commandBus: CommandBus,
    private val conversationQuery: ConversationQueryService,
    private val chatAnswerStream: ChatAnswerStreamService,
) {
    @PostMapping
    @Deprecated("Use POST /api/v1/conversations.")
    fun create(
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody req: StartChatSessionRequest,
    ): ResponseEntity<ChatSessionResponse> {
        val userUuid = UUID.fromString(userId)
        val conversationId = ConversationId.random()
        commandBus.dispatch(
            StartConversationCommand(
                conversationId = conversationId,
                userId = userUuid,
                title = req.title,
                kind = req.kind ?: ConversationKind.PLAIN,
            ),
        )
        val detail =
            conversationQuery.get(conversationId, userUuid)
                ?: error("chat session not visible immediately after create")
        return ResponseEntity.status(HttpStatus.CREATED).body(ChatSessionResponse.of(detail.conversation))
    }

    @GetMapping
    @Deprecated("Use GET /api/v1/conversations.")
    fun list(
        @RequestHeader("X-User-Id") userId: String,
    ): List<ChatSessionResponse> =
        conversationQuery
            .list(UUID.fromString(userId))
            .map(ChatSessionResponse::of)

    @GetMapping("/{id}")
    @Deprecated("Use GET /api/v1/conversations/{id}, which returns a typed ConversationDetailResponse.")
    fun get(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<Map<String, Any>> {
        val detail =
            conversationQuery.get(ConversationId(id), UUID.fromString(userId))
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            mapOf(
                "session" to ChatSessionResponse.of(detail.conversation),
                "messages" to detail.messages.map(ChatMessageResponse::of),
            ),
        )
    }

    @PostMapping("/{id}/messages")
    @Deprecated("Use POST /api/v1/conversations/{id}/messages.")
    fun appendMessage(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody req: AppendChatMessageRequest,
    ): ResponseEntity<ChatMessageResponse> {
        val userUuid = UUID.fromString(userId)
        val conversationId = ConversationId(id)
        // Same ownership check as the canonical controller: the alias
        // delegates to ConversationQueryService, so it inherits the
        // check rather than reimplementing it (see #80).
        conversationQuery.get(conversationId, userUuid) ?: return ResponseEntity.notFound().build()
        val messageId = ConversationMessageId.random()
        commandBus.dispatch(
            AppendConversationMessageCommand(
                messageId = messageId,
                conversationId = conversationId,
                role = req.role,
                body = req.body,
            ),
        )
        val detail = conversationQuery.get(conversationId, userUuid) ?: return ResponseEntity.notFound().build()
        val message =
            detail.messages.firstOrNull { it.id == messageId }
                ?: error("message not visible immediately after append")
        return ResponseEntity.status(HttpStatus.CREATED).body(ChatMessageResponse.of(message))
    }

    // Excluded from the OpenAPI contract for the same reason as the
    // canonical controller's stream endpoint (see there); this alias
    // exists purely so an in-flight stream connection from an old
    // client keeps working.
    @Hidden
    @PostMapping("/{id}/messages/stream")
    fun streamMessage(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody req: AppendChatMessageRequest,
    ): ResponseEntity<SseEmitter> {
        val conversationId = ConversationId(id)
        conversationQuery.get(conversationId, UUID.fromString(userId)) ?: return ResponseEntity.notFound().build()
        val emitter = chatAnswerStream.stream(conversationId, req.body)
        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    @DeleteMapping("/{id}")
    @Deprecated("Use DELETE /api/v1/conversations/{id}.")
    fun archive(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<Unit> {
        commandBus.dispatch(
            ArchiveConversationCommand(
                conversationId = ConversationId(id),
                userId = UUID.fromString(userId),
            ),
        )
        return ResponseEntity.noContent().build()
    }
}

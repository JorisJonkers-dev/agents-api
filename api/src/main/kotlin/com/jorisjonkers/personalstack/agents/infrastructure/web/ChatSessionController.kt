package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.chat.ChatAnswerStreamService
import com.jorisjonkers.personalstack.agents.application.command.AppendChatMessageCommand
import com.jorisjonkers.personalstack.agents.application.command.ArchiveChatSessionCommand
import com.jorisjonkers.personalstack.agents.application.command.StartChatSessionCommand
import com.jorisjonkers.personalstack.agents.application.query.ChatSessionQueryService
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionKind
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

@RestController
@RequestMapping("/api/v1/chat-sessions")
class ChatSessionController(
    private val commandBus: CommandBus,
    private val chatSessionQuery: ChatSessionQueryService,
    private val chatAnswerStream: ChatAnswerStreamService,
) {
    @PostMapping
    fun create(
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody req: StartChatSessionRequest,
    ): ResponseEntity<ChatSessionResponse> {
        val userUuid = UUID.fromString(userId)
        val sessionId = ChatSessionId.random()
        commandBus.dispatch(
            StartChatSessionCommand(
                sessionId = sessionId,
                userId = userUuid,
                title = req.title,
                kind = req.kind ?: ChatSessionKind.PLAIN,
            ),
        )
        val detail =
            chatSessionQuery.get(sessionId)
                ?: error("chat session not visible immediately after create")
        return ResponseEntity.status(HttpStatus.CREATED).body(ChatSessionResponse.of(detail.session))
    }

    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: String,
    ): List<ChatSessionResponse> =
        chatSessionQuery
            .list(UUID.fromString(userId))
            .map(ChatSessionResponse::of)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val detail = chatSessionQuery.get(ChatSessionId(id)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            mapOf(
                "session" to ChatSessionResponse.of(detail.session),
                "messages" to detail.messages.map(ChatMessageResponse::of),
            ),
        )
    }

    @PostMapping("/{id}/messages")
    fun appendMessage(
        @PathVariable id: UUID,
        @Valid @RequestBody req: AppendChatMessageRequest,
    ): ResponseEntity<ChatMessageResponse> {
        val messageId = ChatMessageId.random()
        commandBus.dispatch(
            AppendChatMessageCommand(
                messageId = messageId,
                sessionId = ChatSessionId(id),
                role = req.role,
                body = req.body,
            ),
        )
        val detail = chatSessionQuery.get(ChatSessionId(id)) ?: return ResponseEntity.notFound().build()
        val message =
            detail.messages.firstOrNull { it.id == messageId }
                ?: error("message not visible immediately after append")
        return ResponseEntity.status(HttpStatus.CREATED).body(ChatMessageResponse.of(message))
    }

    // Excluded from the OpenAPI contract: an SSE/text-event-stream
    // endpoint cannot be modelled usefully by openapi-typescript, and the
    // UI consumes it through a hand-written fetch + ReadableStream reader
    // rather than the generated client. Keeping it out of the spec leaves
    // the generated types in sync without a degenerate stream type.
    @Hidden
    @PostMapping("/{id}/messages/stream")
    fun streamMessage(
        @PathVariable id: UUID,
        @Valid @RequestBody req: AppendChatMessageRequest,
    ): ResponseEntity<SseEmitter> {
        val emitter = chatAnswerStream.stream(ChatSessionId(id), req.body)
        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    @DeleteMapping("/{id}")
    fun archive(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<Void> {
        commandBus.dispatch(
            ArchiveChatSessionCommand(
                sessionId = ChatSessionId(id),
                userId = UUID.fromString(userId),
            ),
        )
        return ResponseEntity.noContent().build()
    }
}

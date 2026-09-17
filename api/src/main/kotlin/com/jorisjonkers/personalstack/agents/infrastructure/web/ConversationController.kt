package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.chat.ChatAnswerStreamService
import com.jorisjonkers.personalstack.agents.application.command.AppendConversationMessageCommand
import com.jorisjonkers.personalstack.agents.application.command.ArchiveConversationCommand
import com.jorisjonkers.personalstack.agents.application.command.StartConversationCommand
import com.jorisjonkers.personalstack.agents.application.query.ConversationQueryService
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageId
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AppendConversationMessageRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.ConversationDetailResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.ConversationMessageResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.ConversationResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.StartConversationRequest
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
@RequestMapping("/api/v1/conversations")
class ConversationController(
    private val commandBus: CommandBus,
    private val conversationQuery: ConversationQueryService,
    private val chatAnswerStream: ChatAnswerStreamService,
) {
    @PostMapping
    fun create(
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody req: StartConversationRequest,
    ): ResponseEntity<ConversationResponse> {
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
            conversationQuery.get(conversationId)
                ?: error("conversation not visible immediately after create")
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationResponse.of(detail.conversation))
    }

    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: String,
    ): List<ConversationResponse> =
        conversationQuery
            .list(UUID.fromString(userId))
            .map(ConversationResponse::of)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ConversationDetailResponse> {
        val detail = conversationQuery.get(ConversationId(id)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            ConversationDetailResponse(
                conversation = ConversationResponse.of(detail.conversation),
                messages = detail.messages.map(ConversationMessageResponse::of),
            ),
        )
    }

    @PostMapping("/{id}/messages")
    fun appendMessage(
        @PathVariable id: UUID,
        @Valid @RequestBody req: AppendConversationMessageRequest,
    ): ResponseEntity<ConversationMessageResponse> {
        val messageId = ConversationMessageId.random()
        commandBus.dispatch(
            AppendConversationMessageCommand(
                messageId = messageId,
                conversationId = ConversationId(id),
                role = req.role,
                body = req.body,
            ),
        )
        val detail = conversationQuery.get(ConversationId(id)) ?: return ResponseEntity.notFound().build()
        val message =
            detail.messages.firstOrNull { it.id == messageId }
                ?: error("message not visible immediately after append")
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationMessageResponse.of(message))
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
        @Valid @RequestBody req: AppendConversationMessageRequest,
    ): ResponseEntity<SseEmitter> {
        val emitter = chatAnswerStream.stream(ConversationId(id), req.body)
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

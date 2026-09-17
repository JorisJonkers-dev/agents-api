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
import io.swagger.v3.oas.annotations.responses.ApiResponse
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
    // springdoc can't infer the status from a dynamically built
    // ResponseEntity; document the real 201 so the spec is truthful.
    @ApiResponse(responseCode = "201", description = "Created")
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
            conversationQuery.get(conversationId, userUuid)
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
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<ConversationDetailResponse> {
        val detail =
            conversationQuery.get(ConversationId(id), UUID.fromString(userId))
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ConversationDetailResponse.of(detail.conversation, detail.messages))
    }

    // Restores the legacy /api/v1/conversations/{conversationId}/messages
    // list endpoint, serving the renamed model. Ownership is enforced
    // through the same seam as GET /{id} (see #80): an unowned
    // conversation reads as 404, not an empty list.
    @GetMapping("/{id}/messages")
    fun listMessages(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<List<ConversationMessageResponse>> {
        val detail =
            conversationQuery.get(ConversationId(id), UUID.fromString(userId))
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(detail.messages.map(ConversationMessageResponse::of))
    }

    @PostMapping("/{id}/messages")
    // springdoc can't infer the status from a dynamically built
    // ResponseEntity; document the real 201 so the spec is truthful.
    @ApiResponse(responseCode = "201", description = "Created")
    fun appendMessage(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody req: AppendConversationMessageRequest,
    ): ResponseEntity<ConversationMessageResponse> {
        val userUuid = UUID.fromString(userId)
        val conversationId = ConversationId(id)
        // Ownership is resolved before dispatch, so a refused request has
        // no side effect (see #80).
        conversationQuery.get(conversationId, userUuid) ?: return ResponseEntity.notFound().build()
        val messageId = ConversationMessageId.random()
        commandBus.dispatch(
            AppendConversationMessageCommand(
                messageId = messageId,
                conversationId = conversationId,
                role = req.resolvedRole(),
                body = req.resolvedBody(),
            ),
        )
        val detail = conversationQuery.get(conversationId, userUuid) ?: return ResponseEntity.notFound().build()
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
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody req: AppendConversationMessageRequest,
    ): ResponseEntity<SseEmitter> {
        val conversationId = ConversationId(id)
        // Ownership is resolved before streaming starts, so a refused
        // request never drives generation or token spend (see #80).
        conversationQuery.get(conversationId, UUID.fromString(userId)) ?: return ResponseEntity.notFound().build()
        val emitter = chatAnswerStream.stream(conversationId, req.resolvedBody())
        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    @DeleteMapping("/{id}")
    // springdoc can't infer the status from a dynamically built
    // ResponseEntity; document the real 204 so the spec is truthful.
    @ApiResponse(responseCode = "204", description = "No Content")
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

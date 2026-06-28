package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.command.SendMessageCommand
import com.jorisjonkers.personalstack.agents.application.query.GetMessageQueryService
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.MessageId
import com.jorisjonkers.personalstack.agents.domain.model.MessageRole
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.MessageResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.SendMessageRequest
import com.jorisjonkers.personalstack.common.command.CommandBus
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
class MessageController(
    private val commandBus: CommandBus,
    private val getMessageQueryService: GetMessageQueryService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun send(
        @PathVariable conversationId: UUID,
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody request: SendMessageRequest,
    ): MessageResponse {
        val convId = ConversationId(conversationId)
        val messageId = MessageId(UUID.randomUUID())
        commandBus.dispatch(
            SendMessageCommand(
                messageId = messageId,
                conversationId = convId,
                userId = userId,
                content = request.content,
                role = MessageRole.USER,
            ),
        )
        val saved =
            getMessageQueryService
                .findByConversationId(convId)
                .firstOrNull { it.id == messageId }
                ?: error("Message not found after saving")
        return MessageResponse.from(saved)
    }

    @GetMapping
    fun list(
        @PathVariable conversationId: UUID,
    ): List<MessageResponse> {
        val convId = ConversationId(conversationId)
        return getMessageQueryService
            .findByConversationId(convId)
            .map { MessageResponse.from(it) }
    }
}

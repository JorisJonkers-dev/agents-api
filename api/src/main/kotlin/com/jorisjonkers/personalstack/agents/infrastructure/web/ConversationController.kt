package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.command.ArchiveConversationCommand
import com.jorisjonkers.personalstack.agents.application.command.StartConversationCommand
import com.jorisjonkers.personalstack.agents.application.query.GetConversationQueryService
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.ConversationResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.CreateConversationRequest
import com.jorisjonkers.personalstack.common.command.CommandBus
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
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
@RequestMapping("/api/v1/conversations")
class ConversationController(
    private val commandBus: CommandBus,
    private val getConversationQueryService: GetConversationQueryService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody request: CreateConversationRequest,
    ): ConversationResponse {
        val userUuid = UUID.fromString(userId)
        val conversationId = ConversationId(UUID.randomUUID())
        commandBus.dispatch(
            StartConversationCommand(
                conversationId = conversationId,
                userId = userUuid,
                title = request.title,
            ),
        )
        val created = getConversationQueryService.findById(conversationId)
        return ConversationResponse.from(created)
    }

    @GetMapping("/{id}")
    fun getById(
        @PathVariable id: UUID,
    ): ConversationResponse {
        val conversation = getConversationQueryService.findById(ConversationId(id))
        return ConversationResponse.from(conversation)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun archive(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: String,
    ) {
        commandBus.dispatch(ArchiveConversationCommand(conversationId = ConversationId(id), userId = userId))
    }

    @GetMapping
    fun listByUser(
        @RequestHeader("X-User-Id") userId: String,
    ): List<ConversationResponse> {
        val userUuid = UUID.fromString(userId)
        return getConversationQueryService
            .findByUserId(userUuid)
            .map { ConversationResponse.from(it) }
    }
}

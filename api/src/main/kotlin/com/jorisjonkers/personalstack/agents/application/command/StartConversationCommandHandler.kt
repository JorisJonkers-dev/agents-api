package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.event.ConversationStartedEvent
import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class StartConversationCommandHandler(
    private val conversationRepository: ConversationRepository,
    private val eventPublisher: ApplicationEventPublisher,
) : CommandHandler<StartConversationCommand> {
    override fun handle(command: StartConversationCommand) {
        require(command.title.isNotBlank()) { "Title must not be blank" }

        val now = Instant.now()
        val conversation =
            Conversation(
                id = command.conversationId,
                userId = command.userId,
                title = command.title.trim(),
                status = ConversationStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            )
        conversationRepository.save(conversation)

        eventPublisher.publishEvent(
            ConversationStartedEvent(
                conversationId = conversation.id,
                userId = conversation.userId,
            ),
        )
    }
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import com.jorisjonkers.personalstack.common.exception.DomainException
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ArchiveConversationCommandHandler(
    private val conversations: ConversationRepository,
) : CommandHandler<ArchiveConversationCommand> {
    @Transactional
    override fun handle(command: ArchiveConversationCommand) {
        val conversation =
            conversations.findById(command.conversationId)
                ?: throw NotFoundException("Conversation", command.conversationId.value.toString())

        if (conversation.userId != command.userId) {
            throw DomainException(
                "User ${command.userId} does not own conversation ${command.conversationId.value}",
                "FORBIDDEN",
            )
        }
        conversations.save(
            conversation.copy(
                status = ConversationStatus.ARCHIVED,
                updatedAt = Instant.now(),
            ),
        )
    }
}

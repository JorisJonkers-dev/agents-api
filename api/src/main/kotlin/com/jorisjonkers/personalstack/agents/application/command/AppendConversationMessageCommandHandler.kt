package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.port.ConversationMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class AppendConversationMessageCommandHandler(
    private val conversations: ConversationRepository,
    private val messages: ConversationMessageRepository,
) : CommandHandler<AppendConversationMessageCommand> {
    @Transactional
    override fun handle(command: AppendConversationMessageCommand) {
        require(command.body.isNotBlank()) { "conversation message body must not be blank" }
        val conversation =
            conversations.findById(command.conversationId)
                ?: throw NotFoundException("Conversation", command.conversationId.value.toString())
        val now = Instant.now()
        messages.save(
            ConversationMessage(
                id = command.messageId,
                conversationId = conversation.id,
                role = command.role,
                body = command.body,
                createdAt = now,
            ),
        )
        conversations.save(conversation.copy(updatedAt = now))
    }
}

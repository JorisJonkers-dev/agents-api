package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Message
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.agents.domain.port.MessageRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SendMessageCommandHandler(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
) : CommandHandler<SendMessageCommand> {
    override fun handle(command: SendMessageCommand) {
        require(command.content.isNotBlank()) { "Message content must not be blank" }

        conversationRepository.findById(command.conversationId)
            ?: throw NotFoundException("Conversation", command.conversationId.value.toString())

        val message =
            Message(
                id = command.messageId,
                conversationId = command.conversationId,
                role = command.role,
                content = command.content,
                createdAt = Instant.now(),
            )
        messageRepository.save(message)
    }
}

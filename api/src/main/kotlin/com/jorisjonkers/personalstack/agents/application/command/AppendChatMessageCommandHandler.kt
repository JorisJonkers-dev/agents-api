package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ChatMessage
import com.jorisjonkers.personalstack.agents.domain.port.ChatMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class AppendChatMessageCommandHandler(
    private val sessions: ChatSessionRepository,
    private val messages: ChatMessageRepository,
) : CommandHandler<AppendChatMessageCommand> {
    @Transactional
    override fun handle(command: AppendChatMessageCommand) {
        require(command.body.isNotBlank()) { "chat message body must not be blank" }
        val session =
            sessions.findById(command.sessionId)
                ?: throw NotFoundException("ChatSession", command.sessionId.value.toString())
        val now = Instant.now()
        messages.save(
            ChatMessage(
                id = command.messageId,
                sessionId = session.id,
                role = command.role,
                body = command.body,
                createdAt = now,
            ),
        )
        sessions.save(session.copy(updatedAt = now))
    }
}

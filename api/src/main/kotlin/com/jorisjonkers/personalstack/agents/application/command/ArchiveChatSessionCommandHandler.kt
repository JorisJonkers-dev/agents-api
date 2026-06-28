package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import com.jorisjonkers.personalstack.common.exception.DomainException
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ArchiveChatSessionCommandHandler(
    private val sessions: ChatSessionRepository,
) : CommandHandler<ArchiveChatSessionCommand> {
    @Transactional
    override fun handle(command: ArchiveChatSessionCommand) {
        val session =
            sessions.findById(command.sessionId)
                ?: throw NotFoundException("ChatSession", command.sessionId.value.toString())

        if (session.userId != command.userId) {
            throw DomainException(
                "User ${command.userId} does not own chat session ${command.sessionId.value}",
                "FORBIDDEN",
            )
        }
        sessions.save(
            session.copy(
                status = ChatSessionStatus.ARCHIVED,
                updatedAt = Instant.now(),
            ),
        )
    }
}

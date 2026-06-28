package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Spawns a no-Pod chat session for a user. Returns nothing; the
 * caller passes the freshly generated id in so a follow-up read can
 * locate the new row.
 */
@Component
class StartChatSessionCommandHandler(
    private val sessions: ChatSessionRepository,
) : CommandHandler<StartChatSessionCommand> {
    @Transactional
    override fun handle(command: StartChatSessionCommand) {
        val now = Instant.now()
        val title = command.title?.trim()?.takeIf { it.isNotEmpty() }
        sessions.save(
            ChatSession(
                id = command.sessionId,
                userId = command.userId,
                title = title,
                status = ChatSessionStatus.ACTIVE,
                kind = command.kind,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

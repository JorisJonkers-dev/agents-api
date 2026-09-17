package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Starts a no-Pod conversation for a user. Returns nothing; the
 * caller passes the freshly generated id in so a follow-up read can
 * locate the new row.
 */
@Component
class StartConversationCommandHandler(
    private val conversations: ConversationRepository,
) : CommandHandler<StartConversationCommand> {
    @Transactional
    override fun handle(command: StartConversationCommand) {
        val now = Instant.now()
        val title = command.title?.trim()?.takeIf { it.isNotEmpty() }
        conversations.save(
            Conversation(
                id = command.conversationId,
                userId = command.userId,
                title = title,
                status = ConversationStatus.ACTIVE,
                kind = command.kind,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

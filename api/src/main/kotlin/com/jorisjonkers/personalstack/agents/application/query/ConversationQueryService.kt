package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.port.ConversationMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ConversationQueryService(
    private val conversations: ConversationRepository,
    private val messages: ConversationMessageRepository,
) {
    private val log = LoggerFactory.getLogger(ConversationQueryService::class.java)

    data class ConversationDetail(
        val conversation: Conversation,
        val messages: List<ConversationMessage>,
    )

    fun list(userId: UUID): List<Conversation> = conversations.findAllByUserId(userId)

    // Returns nothing for another user's Conversation, identically to an
    // unknown id: this is the single seam every handler reads through, so
    // ownership can never be forgotten by a caller (see #80).
    fun get(
        id: ConversationId,
        userId: UUID,
    ): ConversationDetail? {
        val conversation = conversations.findById(id) ?: return null
        if (conversation.userId != userId) {
            log.warn("refused conversation {} for user {}: not the owner", id.value, userId)
            return null
        }
        return ConversationDetail(conversation, messages.findAllByConversationIdOrderedByTime(id))
    }
}

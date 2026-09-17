package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.port.ConversationMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ConversationQueryService(
    private val conversations: ConversationRepository,
    private val messages: ConversationMessageRepository,
) {
    data class ConversationDetail(
        val conversation: Conversation,
        val messages: List<ConversationMessage>,
    )

    fun list(userId: UUID): List<Conversation> = conversations.findAllByUserId(userId)

    fun get(id: ConversationId): ConversationDetail? {
        val conversation = conversations.findById(id) ?: return null
        return ConversationDetail(conversation, messages.findAllByConversationIdOrderedByTime(id))
    }
}

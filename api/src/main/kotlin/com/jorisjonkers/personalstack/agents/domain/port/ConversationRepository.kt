package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import java.util.UUID

interface ConversationRepository {
    fun save(conversation: Conversation): Conversation

    fun findById(id: ConversationId): Conversation?

    fun findAllByUserId(userId: UUID): List<Conversation>

    fun delete(id: ConversationId)
}

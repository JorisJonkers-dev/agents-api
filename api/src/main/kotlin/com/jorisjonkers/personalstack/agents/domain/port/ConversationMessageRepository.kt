package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageId

interface ConversationMessageRepository {
    fun save(message: ConversationMessage): ConversationMessage

    fun findById(id: ConversationMessageId): ConversationMessage?

    fun findAllByConversationIdOrderedByTime(conversationId: ConversationId): List<ConversationMessage>

    fun deleteAllByConversationId(conversationId: ConversationId)
}

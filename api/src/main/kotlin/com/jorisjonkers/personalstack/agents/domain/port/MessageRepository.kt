package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.Message

interface MessageRepository {
    fun save(message: Message): Message

    fun findByConversationId(id: ConversationId): List<Message>
}

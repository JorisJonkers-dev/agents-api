package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.ChatMessage
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId

interface ChatMessageRepository {
    fun save(message: ChatMessage): ChatMessage

    fun findById(id: ChatMessageId): ChatMessage?

    fun findAllBySessionIdOrderedByTime(sessionId: ChatSessionId): List<ChatMessage>

    fun deleteAllBySessionId(sessionId: ChatSessionId)
}

package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.Message
import com.jorisjonkers.personalstack.agents.domain.port.MessageRepository
import org.springframework.stereotype.Service

@Service
class GetMessageQueryService(
    private val messageRepository: MessageRepository,
) {
    fun findByConversationId(conversationId: ConversationId): List<Message> =
        messageRepository.findByConversationId(conversationId)
}

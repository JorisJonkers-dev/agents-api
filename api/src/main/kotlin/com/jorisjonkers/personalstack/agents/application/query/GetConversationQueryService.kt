package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class GetConversationQueryService(
    private val conversationRepository: ConversationRepository,
) {
    fun findById(id: ConversationId): Conversation =
        conversationRepository.findById(id)
            ?: throw NotFoundException("Conversation", id.value.toString())

    fun findByUserId(userId: UUID): List<Conversation> = conversationRepository.findByUserId(userId)
}

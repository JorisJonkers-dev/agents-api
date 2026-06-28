package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.ChatMessage
import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.port.ChatMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ChatSessionQueryService(
    private val sessions: ChatSessionRepository,
    private val messages: ChatMessageRepository,
) {
    data class ChatSessionDetail(
        val session: ChatSession,
        val messages: List<ChatMessage>,
    )

    fun list(userId: UUID): List<ChatSession> = sessions.findAllByUserId(userId)

    fun get(id: ChatSessionId): ChatSessionDetail? {
        val session = sessions.findById(id) ?: return null
        return ChatSessionDetail(session, messages.findAllBySessionIdOrderedByTime(id))
    }
}

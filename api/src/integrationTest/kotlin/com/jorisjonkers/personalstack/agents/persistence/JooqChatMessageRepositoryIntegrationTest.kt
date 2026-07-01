package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessage
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageRole
import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionKind
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.ChatMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class JooqChatMessageRepositoryIntegrationTest
    @Autowired
    constructor(
        private val messages: ChatMessageRepository,
        private val sessions: ChatSessionRepository,
    ) : IntegrationTestBase {
        private fun newSession(): ChatSession =
            ChatSession(
                id = ChatSessionId.random(),
                userId = UUID.randomUUID(),
                title = "session",
                status = ChatSessionStatus.ACTIVE,
                kind = ChatSessionKind.PLAIN,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ).also(sessions::save)

        private fun newMessage(
            sessionId: ChatSessionId,
            body: String = "hello",
            createdAt: Instant = Instant.now(),
        ) = ChatMessage(
            id = ChatMessageId.random(),
            sessionId = sessionId,
            role = ChatMessageRole.USER,
            body = body,
            createdAt = createdAt,
        )

        @Test
        fun saveAndFindByIdRoundTrip() {
            val s = newSession()
            val m = newMessage(s.id)
            messages.save(m)
            val loaded = (messages.findById(m.id)).required()
            assertThat(loaded.body).isEqualTo("hello")
        }

        @Test
        fun findallbysessionidorderedbytimeReturnsMessagesChronologically() {
            val s = newSession()
            val first = newMessage(s.id, body = "first", createdAt = Instant.parse("2025-01-01T00:00:00Z"))
            val second = newMessage(s.id, body = "second", createdAt = Instant.parse("2025-01-01T00:01:00Z"))
            val third = newMessage(s.id, body = "third", createdAt = Instant.parse("2025-01-01T00:02:00Z"))
            // Save out of order on purpose.
            messages.save(third)
            messages.save(first)
            messages.save(second)

            val ordered = messages.findAllBySessionIdOrderedByTime(s.id)
            assertThat(ordered.map { it.body }).containsExactly("first", "second", "third")
        }

        @Test
        fun cascadingDeleteRemovesMessagesWhenTheSessionIsRemoved() {
            val s = newSession()
            messages.save(newMessage(s.id))
            messages.save(newMessage(s.id))
            sessions.delete(s.id)
            assertThat(messages.findAllBySessionIdOrderedByTime(s.id)).isEmpty()
        }

        @Test
        fun deleteallbysessionidRemovesTheRows() {
            val s = newSession()
            messages.save(newMessage(s.id))
            messages.save(newMessage(s.id))
            messages.deleteAllBySessionId(s.id)
            assertThat(messages.findAllBySessionIdOrderedByTime(s.id)).isEmpty()
        }
    }

package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageRole
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class JooqConversationMessageRepositoryIntegrationTest
    @Autowired
    constructor(
        private val messages: ConversationMessageRepository,
        private val conversations: ConversationRepository,
    ) : IntegrationTestBase {
        private fun newConversation(): Conversation =
            Conversation(
                id = ConversationId.random(),
                userId = UUID.randomUUID(),
                title = "conversation",
                status = ConversationStatus.ACTIVE,
                kind = ConversationKind.PLAIN,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ).also(conversations::save)

        private fun newMessage(
            conversationId: ConversationId,
            body: String = "hello",
            createdAt: Instant = Instant.now(),
        ) = ConversationMessage(
            id = ConversationMessageId.random(),
            conversationId = conversationId,
            role = ConversationMessageRole.USER,
            body = body,
            createdAt = createdAt,
        )

        @Test
        fun saveAndFindByIdRoundTrip() {
            val c = newConversation()
            val m = newMessage(c.id)
            messages.save(m)
            val loaded = (messages.findById(m.id)).required()
            assertThat(loaded.body).isEqualTo("hello")
        }

        @Test
        fun findallbyconversationidorderedbytimeReturnsMessagesChronologically() {
            val c = newConversation()
            val first = newMessage(c.id, body = "first", createdAt = Instant.parse("2025-01-01T00:00:00Z"))
            val second = newMessage(c.id, body = "second", createdAt = Instant.parse("2025-01-01T00:01:00Z"))
            val third = newMessage(c.id, body = "third", createdAt = Instant.parse("2025-01-01T00:02:00Z"))
            // Save out of order on purpose.
            messages.save(third)
            messages.save(first)
            messages.save(second)

            val ordered = messages.findAllByConversationIdOrderedByTime(c.id)
            assertThat(ordered.map { it.body }).containsExactly("first", "second", "third")
        }

        @Test
        fun cascadingDeleteRemovesMessagesWhenTheConversationIsRemoved() {
            val c = newConversation()
            messages.save(newMessage(c.id))
            messages.save(newMessage(c.id))
            conversations.delete(c.id)
            assertThat(messages.findAllByConversationIdOrderedByTime(c.id)).isEmpty()
        }

        @Test
        fun deleteallbyconversationidRemovesTheRows() {
            val c = newConversation()
            messages.save(newMessage(c.id))
            messages.save(newMessage(c.id))
            messages.deleteAllByConversationId(c.id)
            assertThat(messages.findAllByConversationIdOrderedByTime(c.id)).isEmpty()
        }
    }

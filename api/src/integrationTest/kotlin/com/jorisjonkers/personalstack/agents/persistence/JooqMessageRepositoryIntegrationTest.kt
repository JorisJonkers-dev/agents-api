package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.model.Message
import com.jorisjonkers.personalstack.agents.domain.model.MessageId
import com.jorisjonkers.personalstack.agents.domain.model.MessageRole
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.agents.domain.port.MessageRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class JooqMessageRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var conversationRepository: ConversationRepository

    @Autowired
    private lateinit var messageRepository: MessageRepository

    @Test
    fun `save and findByConversationId returns saved messages`() {
        val conversation = buildConversation()
        conversationRepository.save(conversation)

        val message = buildMessage(conversationId = conversation.id, content = "Hello", role = MessageRole.USER)
        messageRepository.save(message)

        val found = messageRepository.findByConversationId(conversation.id)

        assertThat(found).hasSize(1)
        assertThat(found[0].content).isEqualTo("Hello")
        assertThat(found[0].role).isEqualTo(MessageRole.USER)
    }

    @Test
    fun `findByConversationId returns empty list when no messages exist`() {
        val conversation = buildConversation()
        conversationRepository.save(conversation)

        val found = messageRepository.findByConversationId(conversation.id)

        assertThat(found).isEmpty()
    }

    @Test
    fun `findByConversationId returns messages in ascending creation order`() {
        val conversation = buildConversation()
        conversationRepository.save(conversation)

        val first = buildMessage(conversationId = conversation.id, content = "First", role = MessageRole.USER)
        val second =
            buildMessage(
                conversationId = conversation.id,
                content = "Second",
                role = MessageRole.ASSISTANT,
                createdAt = Instant.now().plusSeconds(1),
            )
        messageRepository.save(first)
        messageRepository.save(second)

        val found = messageRepository.findByConversationId(conversation.id)

        assertThat(found).hasSize(2)
        assertThat(found[0].content).isEqualTo("First")
        assertThat(found[1].content).isEqualTo("Second")
    }

    @Test
    fun `findByConversationId does not return messages for other conversations`() {
        val conversation1 = buildConversation()
        val conversation2 = buildConversation()
        conversationRepository.save(conversation1)
        conversationRepository.save(conversation2)

        messageRepository.save(buildMessage(conversationId = conversation1.id, content = "Conv1 message"))
        messageRepository.save(buildMessage(conversationId = conversation2.id, content = "Conv2 message"))

        val found = messageRepository.findByConversationId(conversation1.id)

        assertThat(found).hasSize(1)
        assertThat(found[0].content).isEqualTo("Conv1 message")
    }

    private fun buildConversation(): Conversation {
        val now = Instant.now()
        return Conversation(
            id = ConversationId(UUID.randomUUID()),
            userId = UUID.randomUUID(),
            title = "Test Conversation",
            status = ConversationStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun buildMessage(
        conversationId: ConversationId,
        content: String = "Test message",
        role: MessageRole = MessageRole.USER,
        createdAt: Instant = Instant.now(),
    ): Message =
        Message(
            id = MessageId(UUID.randomUUID()),
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = createdAt,
        )
}

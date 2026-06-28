package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.Message
import com.jorisjonkers.personalstack.agents.domain.model.MessageId
import com.jorisjonkers.personalstack.agents.domain.model.MessageRole
import com.jorisjonkers.personalstack.agents.domain.port.MessageRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GetMessageQueryServiceTest {
    private val messageRepository = mockk<MessageRepository>()
    private val service = GetMessageQueryService(messageRepository)

    @Test
    fun `findByConversationId returns messages`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val messages =
            listOf(
                buildMessage(conversationId = conversationId, content = "Hello"),
                buildMessage(conversationId = conversationId, content = "World"),
            )
        every { messageRepository.findByConversationId(conversationId) } returns messages

        val result = service.findByConversationId(conversationId)

        assertThat(result).hasSize(2)
        assertThat(result[0].content).isEqualTo("Hello")
        assertThat(result[1].content).isEqualTo("World")
    }

    @Test
    fun `findByConversationId returns empty list when no messages`() {
        val conversationId = ConversationId(UUID.randomUUID())
        every { messageRepository.findByConversationId(conversationId) } returns emptyList()

        val result = service.findByConversationId(conversationId)

        assertThat(result).isEmpty()
    }

    @Test
    fun `findByConversationId delegates to repository`() {
        val conversationId = ConversationId(UUID.randomUUID())
        every { messageRepository.findByConversationId(conversationId) } returns emptyList()

        service.findByConversationId(conversationId)

        verify(exactly = 1) { messageRepository.findByConversationId(conversationId) }
    }

    private fun buildMessage(
        conversationId: ConversationId,
        content: String = "Test message",
    ): Message =
        Message(
            id = MessageId(UUID.randomUUID()),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = content,
            createdAt = Instant.now(),
        )
}

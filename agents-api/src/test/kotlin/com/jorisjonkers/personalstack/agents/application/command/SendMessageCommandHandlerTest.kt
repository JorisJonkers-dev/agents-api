package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.model.Message
import com.jorisjonkers.personalstack.agents.domain.model.MessageId
import com.jorisjonkers.personalstack.agents.domain.model.MessageRole
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.agents.domain.port.MessageRepository
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SendMessageCommandHandlerTest {
    private val conversationRepository = mockk<ConversationRepository>()
    private val messageRepository = mockk<MessageRepository>()
    private val handler = SendMessageCommandHandler(conversationRepository, messageRepository)

    @Test
    fun `handle saves a new message when conversation exists`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val messageId = MessageId(UUID.randomUUID())
        val command =
            SendMessageCommand(
                messageId = messageId,
                conversationId = conversationId,
                userId = UUID.randomUUID().toString(),
                content = "Hello world",
                role = MessageRole.USER,
            )
        val slot = slot<Message>()

        every { conversationRepository.findById(conversationId) } returns buildConversation(conversationId)
        every { messageRepository.save(capture(slot)) } answers { slot.captured }

        handler.handle(command)

        assertThat(slot.captured.id).isEqualTo(messageId)
        assertThat(slot.captured.conversationId).isEqualTo(conversationId)
        assertThat(slot.captured.content).isEqualTo("Hello world")
        assertThat(slot.captured.role).isEqualTo(MessageRole.USER)
    }

    @Test
    fun `handle throws NotFoundException when conversation does not exist`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val command =
            SendMessageCommand(
                messageId = MessageId(UUID.randomUUID()),
                conversationId = conversationId,
                userId = UUID.randomUUID().toString(),
                content = "Hello",
                role = MessageRole.USER,
            )

        every { conversationRepository.findById(conversationId) } returns null

        assertThatThrownBy { handler.handle(command) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `handle throws when content is blank`() {
        val command =
            SendMessageCommand(
                messageId = MessageId(UUID.randomUUID()),
                conversationId = ConversationId(UUID.randomUUID()),
                userId = UUID.randomUUID().toString(),
                content = "   ",
                role = MessageRole.USER,
            )

        assertThatThrownBy { handler.handle(command) }
            .isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { messageRepository.save(any()) }
        verify(exactly = 0) { conversationRepository.findById(any()) }
    }

    private fun buildConversation(id: ConversationId): Conversation {
        val now = Instant.now()
        return Conversation(
            id = id,
            userId = UUID.randomUUID(),
            title = "Test",
            status = ConversationStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }
}

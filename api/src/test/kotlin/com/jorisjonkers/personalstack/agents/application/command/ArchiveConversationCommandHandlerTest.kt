package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.common.exception.DomainException
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ArchiveConversationCommandHandlerTest {
    private val conversationRepository = mockk<ConversationRepository>()
    private val handler = ArchiveConversationCommandHandler(conversationRepository)

    @Test
    fun `handle archives conversation when owner requests it`() {
        val userId = UUID.randomUUID()
        val conversationId = ConversationId(UUID.randomUUID())
        val conversation = buildConversation(id = conversationId, userId = userId)
        val slot = slot<Conversation>()

        every { conversationRepository.findById(conversationId) } returns conversation
        every { conversationRepository.save(capture(slot)) } answers { slot.captured }

        handler.handle(ArchiveConversationCommand(conversationId = conversationId, userId = userId.toString()))

        assertThat(slot.captured.status).isEqualTo(ConversationStatus.ARCHIVED)
    }

    @Test
    fun `handle throws NotFoundException when conversation does not exist`() {
        val conversationId = ConversationId(UUID.randomUUID())

        every { conversationRepository.findById(conversationId) } returns null

        assertThatThrownBy {
            val cmd = ArchiveConversationCommand(conversationId = conversationId, userId = UUID.randomUUID().toString())
            handler.handle(cmd)
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `handle throws DomainException when user does not own the conversation`() {
        val ownerId = UUID.randomUUID()
        val conversationId = ConversationId(UUID.randomUUID())
        val conversation = buildConversation(id = conversationId, userId = ownerId)
        val otherUserId = UUID.randomUUID().toString()

        every { conversationRepository.findById(conversationId) } returns conversation

        assertThatThrownBy {
            handler.handle(ArchiveConversationCommand(conversationId = conversationId, userId = otherUserId))
        }.isInstanceOf(DomainException::class.java)
            .hasMessageContaining("does not own")
    }

    @Test
    fun `handle throws DomainException when userId is not a valid UUID`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val conversation = buildConversation(id = conversationId)

        every { conversationRepository.findById(conversationId) } returns conversation

        assertThatThrownBy {
            handler.handle(ArchiveConversationCommand(conversationId = conversationId, userId = "not-a-uuid"))
        }.isInstanceOf(DomainException::class.java)
    }

    private fun buildConversation(
        id: ConversationId = ConversationId(UUID.randomUUID()),
        userId: UUID = UUID.randomUUID(),
    ): Conversation {
        val now = Instant.now()
        return Conversation(
            id = id,
            userId = userId,
            title = "Test",
            status = ConversationStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }
}

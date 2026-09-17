package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.common.exception.DomainException
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class ArchiveConversationCommandHandlerTest {
    private val conversations = mockk<ConversationRepository>()
    private val handler = ArchiveConversationCommandHandler(conversations)

    private fun conversation(userId: UUID) =
        Conversation(
            id = ConversationId.random(),
            userId = userId,
            title = "x",
            status = ConversationStatus.ACTIVE,
            kind = ConversationKind.PLAIN,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `handle archives the conversation when the owner asks`() {
        val uid = UUID.randomUUID()
        val c = conversation(uid)
        every { conversations.findById(c.id) } returns c
        val saved = slot<Conversation>()
        every { conversations.save(capture(saved)) } answers { saved.captured }

        handler.handle(ArchiveConversationCommand(c.id, uid))

        assertThat(saved.captured.status).isEqualTo(ConversationStatus.ARCHIVED)
        verify { conversations.save(any()) }
    }

    @Test
    fun `handle throws NotFound for an unknown conversation`() {
        every { conversations.findById(any()) } returns null
        assertThrows<NotFoundException> {
            handler.handle(ArchiveConversationCommand(ConversationId.random(), UUID.randomUUID()))
        }
    }

    @Test
    fun `handle rejects a non-owner`() {
        val c = conversation(UUID.randomUUID())
        every { conversations.findById(c.id) } returns c
        assertThrows<DomainException> {
            handler.handle(ArchiveConversationCommand(c.id, UUID.randomUUID()))
        }
    }
}

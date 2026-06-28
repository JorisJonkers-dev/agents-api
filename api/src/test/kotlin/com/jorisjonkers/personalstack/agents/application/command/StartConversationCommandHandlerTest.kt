package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

class StartConversationCommandHandlerTest {
    private val conversationRepository = mockk<ConversationRepository>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val handler = StartConversationCommandHandler(conversationRepository, eventPublisher)

    @Test
    fun `handle creates and saves a new conversation`() {
        val userId = UUID.randomUUID()
        val conversationId = ConversationId(UUID.randomUUID())
        val command = StartConversationCommand(conversationId = conversationId, userId = userId, title = "My Chat")
        val slot = slot<Conversation>()

        every { conversationRepository.save(capture(slot)) } answers { slot.captured }

        handler.handle(command)

        assertThat(slot.captured.id).isEqualTo(conversationId)
        assertThat(slot.captured.userId).isEqualTo(userId)
        assertThat(slot.captured.title).isEqualTo("My Chat")
        assertThat(slot.captured.status).isEqualTo(ConversationStatus.ACTIVE)
        verify { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `handle trims whitespace from title`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val command =
            StartConversationCommand(
                conversationId = conversationId,
                userId = UUID.randomUUID(),
                title = "  Padded Title  ",
            )
        val slot = slot<Conversation>()

        every { conversationRepository.save(capture(slot)) } answers { slot.captured }

        handler.handle(command)

        assertThat(slot.captured.title).isEqualTo("Padded Title")
    }

    @Test
    fun `handle throws when title is blank`() {
        val command =
            StartConversationCommand(
                conversationId = ConversationId(UUID.randomUUID()),
                userId = UUID.randomUUID(),
                title = "   ",
            )

        assertThatThrownBy { handler.handle(command) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageRole
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
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

class AppendConversationMessageCommandHandlerTest {
    private val conversations = mockk<ConversationRepository>()
    private val messages = mockk<ConversationMessageRepository>()
    private val handler = AppendConversationMessageCommandHandler(conversations, messages)

    private val conversation =
        Conversation(
            id = ConversationId.random(),
            userId = UUID.randomUUID(),
            title = "x",
            status = ConversationStatus.ACTIVE,
            kind = ConversationKind.PLAIN,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `handle persists the message and bumps conversation updatedAt`() {
        every { conversations.findById(conversation.id) } returns conversation
        val saved = slot<ConversationMessage>()
        every { messages.save(capture(saved)) } answers { saved.captured }
        every { conversations.save(any()) } answers { firstArg() }

        val mid = ConversationMessageId.random()
        handler.handle(AppendConversationMessageCommand(mid, conversation.id, ConversationMessageRole.USER, "Hello"))

        assertThat(saved.captured.id).isEqualTo(mid)
        assertThat(saved.captured.role).isEqualTo(ConversationMessageRole.USER)
        assertThat(saved.captured.body).isEqualTo("Hello")
        verify { conversations.save(any()) }
    }

    @Test
    fun `handle rejects a blank body`() {
        every { conversations.findById(conversation.id) } returns conversation
        assertThrows<IllegalArgumentException> {
            handler.handle(
                AppendConversationMessageCommand(
                    ConversationMessageId.random(),
                    conversation.id,
                    ConversationMessageRole.USER,
                    "  ",
                ),
            )
        }
    }

    @Test
    fun `handle throws NotFound for an unknown conversation`() {
        every { conversations.findById(any()) } returns null
        assertThrows<NotFoundException> {
            handler.handle(
                AppendConversationMessageCommand(
                    messageId = ConversationMessageId.random(),
                    conversationId = ConversationId.random(),
                    role = ConversationMessageRole.USER,
                    body = "hey",
                ),
            )
        }
    }
}

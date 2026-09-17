package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class StartConversationCommandHandlerTest {
    private val conversations = mockk<ConversationRepository>()
    private val handler = StartConversationCommandHandler(conversations)

    @Test
    fun `handle persists an ACTIVE conversation with trimmed title`() {
        val saved = slot<Conversation>()
        every { conversations.save(capture(saved)) } answers { saved.captured }

        val cid = ConversationId.random()
        val uid = UUID.randomUUID()
        handler.handle(StartConversationCommand(cid, uid, "  My chat  "))

        assertThat(saved.captured.id).isEqualTo(cid)
        assertThat(saved.captured.userId).isEqualTo(uid)
        assertThat(saved.captured.title).isEqualTo("My chat")
        assertThat(saved.captured.status).isEqualTo(ConversationStatus.ACTIVE)
    }

    @Test
    fun `handle treats a blank title as null`() {
        val saved = slot<Conversation>()
        every { conversations.save(capture(saved)) } answers { saved.captured }

        handler.handle(StartConversationCommand(ConversationId.random(), UUID.randomUUID(), "   "))

        assertThat(saved.captured.title).isNull()
    }

    @Test
    fun `handle allows a null title`() {
        val saved = slot<Conversation>()
        every { conversations.save(capture(saved)) } answers { saved.captured }

        handler.handle(StartConversationCommand(ConversationId.random(), UUID.randomUUID(), null))

        assertThat(saved.captured.title).isNull()
    }
}

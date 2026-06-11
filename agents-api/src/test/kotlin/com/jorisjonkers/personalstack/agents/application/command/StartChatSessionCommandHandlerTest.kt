package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class StartChatSessionCommandHandlerTest {
    private val sessions = mockk<ChatSessionRepository>()
    private val handler = StartChatSessionCommandHandler(sessions)

    @Test
    fun `handle persists an ACTIVE session with trimmed title`() {
        val saved = slot<ChatSession>()
        every { sessions.save(capture(saved)) } answers { saved.captured }

        val sid = ChatSessionId.random()
        val uid = UUID.randomUUID()
        handler.handle(StartChatSessionCommand(sid, uid, "  My chat  "))

        assertThat(saved.captured.id).isEqualTo(sid)
        assertThat(saved.captured.userId).isEqualTo(uid)
        assertThat(saved.captured.title).isEqualTo("My chat")
        assertThat(saved.captured.status).isEqualTo(ChatSessionStatus.ACTIVE)
    }

    @Test
    fun `handle treats a blank title as null`() {
        val saved = slot<ChatSession>()
        every { sessions.save(capture(saved)) } answers { saved.captured }

        handler.handle(StartChatSessionCommand(ChatSessionId.random(), UUID.randomUUID(), "   "))

        assertThat(saved.captured.title).isNull()
    }

    @Test
    fun `handle allows a null title`() {
        val saved = slot<ChatSession>()
        every { sessions.save(capture(saved)) } answers { saved.captured }

        handler.handle(StartChatSessionCommand(ChatSessionId.random(), UUID.randomUUID(), null))

        assertThat(saved.captured.title).isNull()
    }
}

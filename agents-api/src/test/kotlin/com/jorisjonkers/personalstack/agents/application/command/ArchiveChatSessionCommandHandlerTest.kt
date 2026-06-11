package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionKind
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
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

class ArchiveChatSessionCommandHandlerTest {
    private val sessions = mockk<ChatSessionRepository>()
    private val handler = ArchiveChatSessionCommandHandler(sessions)

    private fun session(userId: UUID) =
        ChatSession(
            id = ChatSessionId.random(),
            userId = userId,
            title = "x",
            status = ChatSessionStatus.ACTIVE,
            kind = ChatSessionKind.PLAIN,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `handle archives the session when the owner asks`() {
        val uid = UUID.randomUUID()
        val s = session(uid)
        every { sessions.findById(s.id) } returns s
        val saved = slot<ChatSession>()
        every { sessions.save(capture(saved)) } answers { saved.captured }

        handler.handle(ArchiveChatSessionCommand(s.id, uid))

        assertThat(saved.captured.status).isEqualTo(ChatSessionStatus.ARCHIVED)
        verify { sessions.save(any()) }
    }

    @Test
    fun `handle throws NotFound for an unknown session`() {
        every { sessions.findById(any()) } returns null
        assertThrows<NotFoundException> {
            handler.handle(ArchiveChatSessionCommand(ChatSessionId.random(), UUID.randomUUID()))
        }
    }

    @Test
    fun `handle rejects a non-owner`() {
        val s = session(UUID.randomUUID())
        every { sessions.findById(s.id) } returns s
        assertThrows<DomainException> {
            handler.handle(ArchiveChatSessionCommand(s.id, UUID.randomUUID()))
        }
    }
}

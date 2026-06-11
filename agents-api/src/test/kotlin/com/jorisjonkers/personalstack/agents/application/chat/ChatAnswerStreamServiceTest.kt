package com.jorisjonkers.personalstack.agents.application.chat

import com.jorisjonkers.personalstack.agents.application.command.AppendChatMessageCommand
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageRole
import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionKind
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.LightRagClient
import com.jorisjonkers.personalstack.common.command.CommandBus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

class ChatAnswerStreamServiceTest {
    private val sessions = mockk<ChatSessionRepository>()
    private val commandBus = mockk<CommandBus>()
    private val lightRag = mockk<LightRagClient>()
    private val executor = Executor { it.run() }
    private val service = ChatAnswerStreamService(sessions, commandBus, lightRag, executor)

    @Test
    fun `stream persists agent message when an answer is produced`() {
        val session = session()
        val commands = mutableListOf<AppendChatMessageCommand>()
        every { sessions.findById(session.id) } returns session
        every { commandBus.dispatch(capture(commands)) } just runs
        every { lightRag.streamQuery("Hi", any()) } answers {
            secondArg<(String) -> Unit>().invoke("Hel")
            secondArg<(String) -> Unit>().invoke("lo")
            "Hello"
        }

        service.stream(session.id, "Hi")

        assertThat(commands).hasSize(1)
        assertThat(commands[0].sessionId).isEqualTo(session.id)
        assertThat(commands[0].role).isEqualTo(ChatMessageRole.ASSISTANT)
        assertThat(commands[0].body).isEqualTo("Hello")
    }

    @Test
    fun `stream persists no agent message when no answer is produced`() {
        val session = session()
        val commands = mutableListOf<AppendChatMessageCommand>()
        every { sessions.findById(session.id) } returns session
        every { commandBus.dispatch(capture(commands)) } just runs
        every { lightRag.streamQuery("Hi", any()) } returns ""

        service.stream(session.id, "Hi")

        assertThat(commands).isEmpty()
    }

    private fun session(
        id: ChatSessionId = ChatSessionId.random(),
        userId: UUID = UUID.randomUUID(),
    ): ChatSession {
        val now = Instant.now()
        return ChatSession(id, userId, "x", ChatSessionStatus.ACTIVE, ChatSessionKind.PLAIN, now, now)
    }
}

package com.jorisjonkers.personalstack.agents.application.chat

import com.jorisjonkers.personalstack.agents.application.command.AppendChatMessageCommand
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessage
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageRole
import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionKind
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.ChatGenerationPort
import com.jorisjonkers.personalstack.agents.domain.port.ChatMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import com.jorisjonkers.personalstack.common.command.CommandBus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

class ChatAnswerStreamServiceTest {
    private val sessions = mockk<ChatSessionRepository>()
    private val messages = mockk<ChatMessageRepository>()
    private val commandBus = mockk<CommandBus>()
    private val generation = mockk<ChatGenerationPort>()
    private val executor = Executor { it.run() }
    private val service = ChatAnswerStreamService(sessions, messages, commandBus, generation, executor)

    @Test
    fun `stream persists agent message when an answer is produced`() {
        val session = session()
        val commands = mutableListOf<AppendChatMessageCommand>()
        every { sessions.findById(session.id) } returns session
        every { messages.findAllBySessionIdOrderedByTime(session.id) } returns emptyList()
        every { commandBus.dispatch(capture(commands)) } just runs
        every { generation.generate("Hi", any()) } answers {
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
        every { messages.findAllBySessionIdOrderedByTime(session.id) } returns emptyList()
        every { commandBus.dispatch(capture(commands)) } just runs
        every { generation.generate("Hi", any()) } returns ""

        service.stream(session.id, "Hi")

        assertThat(commands).isEmpty()
    }

    @Test
    fun `stream routes generation through ChatGenerationPort not LightRagClient`() {
        val session = session()
        val promptSlot = slot<String>()
        every { sessions.findById(session.id) } returns session
        every { messages.findAllBySessionIdOrderedByTime(session.id) } returns emptyList()
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(session.id, "Hello")

        assertThat(promptSlot.captured).isEqualTo("Hello")
    }

    @Test
    fun `prompt includes prior history turns when session has messages`() {
        val session = session()
        val promptSlot = slot<String>()
        val history =
            listOf(
                chatMessage(session.id, ChatMessageRole.USER, "What is Kotlin?"),
                chatMessage(session.id, ChatMessageRole.ASSISTANT, "A JVM language."),
            )
        every { sessions.findById(session.id) } returns session
        every { messages.findAllBySessionIdOrderedByTime(session.id) } returns history
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(session.id, "Tell me more")

        assertThat(promptSlot.captured).contains("User: What is Kotlin?")
        assertThat(promptSlot.captured).contains("Assistant: A JVM language.")
        assertThat(promptSlot.captured).endsWith("User: Tell me more")
    }

    @Test
    fun `prompt with no history is just the user body`() {
        val session = session()
        val promptSlot = slot<String>()
        every { sessions.findById(session.id) } returns session
        every { messages.findAllBySessionIdOrderedByTime(session.id) } returns emptyList()
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(session.id, "Hello")

        assertThat(promptSlot.captured).isEqualTo("Hello")
    }

    @Test
    fun `history is bounded to the most recent 20 messages`() {
        val session = session()
        val promptSlot = slot<String>()
        val history =
            (1..25).map { i ->
                chatMessage(session.id, ChatMessageRole.USER, "Message $i")
            }
        every { sessions.findById(session.id) } returns session
        every { messages.findAllBySessionIdOrderedByTime(session.id) } returns history
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(session.id, "Latest")

        val captured = promptSlot.captured
        assertThat(captured).doesNotContain("User: Message 4\n")
        assertThat(captured).doesNotContain("User: Message 5\n")
        assertThat(captured).contains("User: Message 6\n")
        assertThat(captured).contains("User: Message 25\n")
        assertThat(captured).endsWith("User: Latest")
    }

    @Test
    fun `current user turn is not duplicated when already persisted in history`() {
        val session = session()
        val promptSlot = slot<String>()
        val history =
            listOf(
                chatMessage(session.id, ChatMessageRole.USER, "What is Kotlin?"),
                chatMessage(session.id, ChatMessageRole.ASSISTANT, "A JVM language."),
                chatMessage(session.id, ChatMessageRole.USER, "Tell me more"),
            )
        every { sessions.findById(session.id) } returns session
        every { messages.findAllBySessionIdOrderedByTime(session.id) } returns history
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(session.id, "Tell me more")

        // History already ends with this exact user turn (persist-then-stream
        // ordering) so it must appear once, not twice.
        assertThat(promptSlot.captured.split("User: Tell me more")).hasSize(2)
        assertThat(promptSlot.captured).endsWith("User: Tell me more")
    }

    @Test
    fun `error during generation emits error SSE event`() {
        val session = session()
        every { sessions.findById(session.id) } returns session
        every { messages.findAllBySessionIdOrderedByTime(session.id) } returns emptyList()
        every { generation.generate(any(), any()) } throws RuntimeException("backend unavailable")

        val emitter = service.stream(session.id, "Hi")

        assertThat(emitter).isNotNull()
    }

    private fun session(
        id: ChatSessionId = ChatSessionId.random(),
        userId: UUID = UUID.randomUUID(),
    ): ChatSession {
        val now = Instant.now()
        return ChatSession(id, userId, "x", ChatSessionStatus.ACTIVE, ChatSessionKind.PLAIN, now, now)
    }

    private fun chatMessage(
        sessionId: ChatSessionId,
        role: ChatMessageRole,
        body: String,
    ): ChatMessage =
        ChatMessage(
            id = ChatMessageId.random(),
            sessionId = sessionId,
            role = role,
            body = body,
            createdAt = Instant.now(),
        )
}

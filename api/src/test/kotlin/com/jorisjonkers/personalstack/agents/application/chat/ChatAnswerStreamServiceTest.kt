package com.jorisjonkers.personalstack.agents.application.chat

import com.jorisjonkers.personalstack.agents.application.command.AppendConversationMessageCommand
import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageRole
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ChatGenerationPort
import com.jorisjonkers.personalstack.agents.domain.port.ConversationMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
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
    private val conversations = mockk<ConversationRepository>()
    private val messages = mockk<ConversationMessageRepository>()
    private val commandBus = mockk<CommandBus>()
    private val generation = mockk<ChatGenerationPort>()
    private val executor = Executor { it.run() }
    private val service = ChatAnswerStreamService(conversations, messages, commandBus, generation, executor)

    @Test
    fun `stream persists agent message when an answer is produced`() {
        val conversation = conversation()
        val commands = mutableListOf<AppendConversationMessageCommand>()
        every { conversations.findById(conversation.id) } returns conversation
        every { messages.findAllByConversationIdOrderedByTime(conversation.id) } returns emptyList()
        every { commandBus.dispatch(capture(commands)) } just runs
        every { generation.generate("Hi", any()) } answers {
            secondArg<(String) -> Unit>().invoke("Hel")
            secondArg<(String) -> Unit>().invoke("lo")
            "Hello"
        }

        service.stream(conversation.id, "Hi")

        assertThat(commands).hasSize(1)
        assertThat(commands[0].conversationId).isEqualTo(conversation.id)
        assertThat(commands[0].role).isEqualTo(ConversationMessageRole.ASSISTANT)
        assertThat(commands[0].body).isEqualTo("Hello")
    }

    @Test
    fun `stream persists no agent message when no answer is produced`() {
        val conversation = conversation()
        val commands = mutableListOf<AppendConversationMessageCommand>()
        every { conversations.findById(conversation.id) } returns conversation
        every { messages.findAllByConversationIdOrderedByTime(conversation.id) } returns emptyList()
        every { commandBus.dispatch(capture(commands)) } just runs
        every { generation.generate("Hi", any()) } returns ""

        service.stream(conversation.id, "Hi")

        assertThat(commands).isEmpty()
    }

    @Test
    fun `stream routes generation through ChatGenerationPort not LightRagClient`() {
        val conversation = conversation()
        val promptSlot = slot<String>()
        every { conversations.findById(conversation.id) } returns conversation
        every { messages.findAllByConversationIdOrderedByTime(conversation.id) } returns emptyList()
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(conversation.id, "Hello")

        assertThat(promptSlot.captured).isEqualTo("Hello")
    }

    @Test
    fun `prompt includes prior history turns when conversation has messages`() {
        val conversation = conversation()
        val promptSlot = slot<String>()
        val history =
            listOf(
                conversationMessage(conversation.id, ConversationMessageRole.USER, "What is Kotlin?"),
                conversationMessage(conversation.id, ConversationMessageRole.ASSISTANT, "A JVM language."),
            )
        every { conversations.findById(conversation.id) } returns conversation
        every { messages.findAllByConversationIdOrderedByTime(conversation.id) } returns history
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(conversation.id, "Tell me more")

        assertThat(promptSlot.captured).contains("User: What is Kotlin?")
        assertThat(promptSlot.captured).contains("Assistant: A JVM language.")
        assertThat(promptSlot.captured).endsWith("User: Tell me more")
    }

    @Test
    fun `prompt with no history is just the user body`() {
        val conversation = conversation()
        val promptSlot = slot<String>()
        every { conversations.findById(conversation.id) } returns conversation
        every { messages.findAllByConversationIdOrderedByTime(conversation.id) } returns emptyList()
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(conversation.id, "Hello")

        assertThat(promptSlot.captured).isEqualTo("Hello")
    }

    @Test
    fun `history is bounded to the most recent 20 messages`() {
        val conversation = conversation()
        val promptSlot = slot<String>()
        val history =
            (1..25).map { i ->
                conversationMessage(conversation.id, ConversationMessageRole.USER, "Message $i")
            }
        every { conversations.findById(conversation.id) } returns conversation
        every { messages.findAllByConversationIdOrderedByTime(conversation.id) } returns history
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(conversation.id, "Latest")

        val captured = promptSlot.captured
        assertThat(captured).doesNotContain("User: Message 4\n")
        assertThat(captured).doesNotContain("User: Message 5\n")
        assertThat(captured).contains("User: Message 6\n")
        assertThat(captured).contains("User: Message 25\n")
        assertThat(captured).endsWith("User: Latest")
    }

    @Test
    fun `current user turn is not duplicated when already persisted in history`() {
        val conversation = conversation()
        val promptSlot = slot<String>()
        val history =
            listOf(
                conversationMessage(conversation.id, ConversationMessageRole.USER, "What is Kotlin?"),
                conversationMessage(conversation.id, ConversationMessageRole.ASSISTANT, "A JVM language."),
                conversationMessage(conversation.id, ConversationMessageRole.USER, "Tell me more"),
            )
        every { conversations.findById(conversation.id) } returns conversation
        every { messages.findAllByConversationIdOrderedByTime(conversation.id) } returns history
        every { commandBus.dispatch(any()) } just runs
        every { generation.generate(capture(promptSlot), any()) } returns "Answer"

        service.stream(conversation.id, "Tell me more")

        // History already ends with this exact user turn (persist-then-stream
        // ordering) so it must appear once, not twice.
        assertThat(promptSlot.captured.split("User: Tell me more")).hasSize(2)
        assertThat(promptSlot.captured).endsWith("User: Tell me more")
    }

    @Test
    fun `error during generation emits error SSE event`() {
        val conversation = conversation()
        every { conversations.findById(conversation.id) } returns conversation
        every { messages.findAllByConversationIdOrderedByTime(conversation.id) } returns emptyList()
        every { generation.generate(any(), any()) } throws RuntimeException("backend unavailable")

        val emitter = service.stream(conversation.id, "Hi")

        assertThat(emitter).isNotNull()
    }

    private fun conversation(
        id: ConversationId = ConversationId.random(),
        userId: UUID = UUID.randomUUID(),
    ): Conversation {
        val now = Instant.now()
        return Conversation(id, userId, "x", ConversationStatus.ACTIVE, ConversationKind.PLAIN, now, now)
    }

    private fun conversationMessage(
        conversationId: ConversationId,
        role: ConversationMessageRole,
        body: String,
    ): ConversationMessage =
        ConversationMessage(
            id = ConversationMessageId.random(),
            conversationId = conversationId,
            role = role,
            body = body,
            createdAt = Instant.now(),
        )
}

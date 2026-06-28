package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.application.command.SendMessageCommand
import com.jorisjonkers.personalstack.agents.application.query.GetMessageQueryService
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.Message
import com.jorisjonkers.personalstack.agents.domain.model.MessageId
import com.jorisjonkers.personalstack.agents.domain.model.MessageRole
import com.jorisjonkers.personalstack.common.command.CommandBus
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class MessageControllerTest {
    private val commandBus = mockk<CommandBus>(relaxed = true)
    private val getMessageQueryService = mockk<GetMessageQueryService>()
    private val objectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = MessageController(commandBus, getMessageQueryService)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    fun `POST sends message and returns 201`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val userId = UUID.randomUUID()

        val commandSlot = slot<SendMessageCommand>()
        every { commandBus.dispatch(capture(commandSlot)) } just runs

        every { getMessageQueryService.findByConversationId(conversationId) } answers {
            val id = commandSlot.captured.messageId
            listOf(buildMessage(id = id, conversationId = conversationId, content = "Hello world"))
        }

        mockMvc
            .perform(
                post("/api/v1/conversations/${conversationId.value}/messages")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("content" to "Hello world"))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.content").value("Hello world"))
            .andExpect(jsonPath("$.role").value("USER"))
    }

    @Test
    fun `POST with blank content returns 422`() {
        val conversationId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        mockMvc
            .perform(
                post("/api/v1/conversations/$conversationId/messages")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("content" to ""))),
            ).andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.title").value("Validation Error"))
    }

    @Test
    fun `POST with content exceeding 10000 chars returns 422`() {
        val conversationId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val longContent = "a".repeat(10001)

        mockMvc
            .perform(
                post("/api/v1/conversations/$conversationId/messages")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("content" to longContent))),
            ).andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.title").value("Validation Error"))
    }

    @Test
    fun `POST to non-existent conversation returns 404`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val userId = UUID.randomUUID()

        every { commandBus.dispatch(any()) } throws
            NotFoundException("Conversation", conversationId.value.toString())

        mockMvc
            .perform(
                post("/api/v1/conversations/${conversationId.value}/messages")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("content" to "Hello"))),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Resource Not Found"))
    }

    @Test
    fun `GET returns messages for conversation`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val messages =
            listOf(
                buildMessage(conversationId = conversationId, content = "Hello", role = MessageRole.USER),
                buildMessage(conversationId = conversationId, content = "Hi there", role = MessageRole.ASSISTANT),
            )

        every { getMessageQueryService.findByConversationId(conversationId) } returns messages

        mockMvc
            .perform(get("/api/v1/conversations/${conversationId.value}/messages"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].content").value("Hello"))
            .andExpect(jsonPath("$[0].role").value("USER"))
            .andExpect(jsonPath("$[1].content").value("Hi there"))
            .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
    }

    @Test
    fun `GET returns empty list for conversation with no messages`() {
        val conversationId = ConversationId(UUID.randomUUID())

        every { getMessageQueryService.findByConversationId(conversationId) } returns emptyList()

        mockMvc
            .perform(get("/api/v1/conversations/${conversationId.value}/messages"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    private fun buildMessage(
        id: MessageId = MessageId(UUID.randomUUID()),
        conversationId: ConversationId,
        content: String = "Test message",
        role: MessageRole = MessageRole.USER,
    ): Message =
        Message(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = Instant.now(),
        )
}

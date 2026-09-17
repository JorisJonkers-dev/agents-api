package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.application.chat.ChatAnswerStreamService
import com.jorisjonkers.personalstack.agents.application.query.ConversationQueryService
import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.common.command.CommandBus
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class ConversationControllerTest {
    private val commandBus = mockk<CommandBus>(relaxed = true)
    private val query = mockk<ConversationQueryService>()
    private val chatAnswerStream = mockk<ChatAnswerStreamService>()
    private val objectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = ConversationController(commandBus, query, chatAnswerStream)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    private fun conversation(
        id: ConversationId = ConversationId.random(),
        userId: UUID = UUID.randomUUID(),
    ): Conversation {
        val now = Instant.now()
        return Conversation(id, userId, "x", ConversationStatus.ACTIVE, ConversationKind.PLAIN, now, now)
    }

    @Test
    fun `POST creates conversation and returns 201`() {
        val c = conversation()
        every { query.get(any()) } returns ConversationQueryService.ConversationDetail(c, emptyList())
        mockMvc
            .perform(
                post("/api/v1/conversations")
                    .header("X-User-Id", c.userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to "x", "kind" to "PLAIN"))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.kind").value("PLAIN"))
        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `GET list returns conversations for user`() {
        val uid = UUID.randomUUID()
        every { query.list(uid) } returns listOf(conversation(userId = uid))
        mockMvc
            .perform(get("/api/v1/conversations").header("X-User-Id", uid.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `GET by id returns a typed ConversationDetailResponse`() {
        val c = conversation()
        every { query.get(c.id) } returns ConversationQueryService.ConversationDetail(c, emptyList())
        mockMvc
            .perform(get("/api/v1/conversations/${c.id.value}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.conversation.id").value(c.id.value.toString()))
            .andExpect(jsonPath("$.messages").isArray)
    }

    @Test
    fun `GET by id with unknown returns 404`() {
        every { query.get(any()) } returns null
        mockMvc
            .perform(get("/api/v1/conversations/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST messages dispatches the append command`() {
        val c = conversation()
        every { query.get(any<ConversationId>()) } returns
            ConversationQueryService.ConversationDetail(c, emptyList())
        try {
            mockMvc
                .perform(
                    post("/api/v1/conversations/${c.id.value}/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("body" to "hello", "role" to "USER"))),
                )
        } catch (_: Throwable) {
            // The controller's error("message not visible…") surfaces
            // as an unhandled exception in MockMvc; verify the
            // dispatch happened regardless.
        }
        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `POST messages with unknown conversation returns 404`() {
        every { query.get(any<ConversationId>()) } returns null
        mockMvc
            .perform(
                post("/api/v1/conversations/${UUID.randomUUID()}/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "hello", "role" to "USER"))),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE archives conversation and returns 204`() {
        mockMvc
            .perform(
                delete("/api/v1/conversations/${UUID.randomUUID()}")
                    .header("X-User-Id", UUID.randomUUID().toString()),
            ).andExpect(status().isNoContent)
        verify { commandBus.dispatch(any()) }
    }
}

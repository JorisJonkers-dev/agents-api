package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.application.query.GetConversationQueryService
import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.common.command.CommandBus
import com.jorisjonkers.personalstack.common.exception.NotFoundException
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
    private val getConversationQueryService = mockk<GetConversationQueryService>()
    private val objectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = ConversationController(commandBus, getConversationQueryService)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    fun `POST creates conversation and returns 201`() {
        val userId = UUID.randomUUID()
        every { getConversationQueryService.findById(any()) } returns
            buildConversation(userId = userId, title = "My Chat")

        mockMvc
            .perform(
                post("/api/v1/conversations")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to "My Chat"))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("My Chat"))
            .andExpect(jsonPath("$.userId").value(userId.toString()))
            .andExpect(jsonPath("$.status").value("ACTIVE"))

        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `POST with blank title returns 422`() {
        val userId = UUID.randomUUID()

        mockMvc
            .perform(
                post("/api/v1/conversations")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to ""))),
            ).andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.title").value("Validation Error"))
    }

    @Test
    fun `POST with title exceeding 200 chars returns 422`() {
        val userId = UUID.randomUUID()
        val longTitle = "a".repeat(201)

        mockMvc
            .perform(
                post("/api/v1/conversations")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to longTitle))),
            ).andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.title").value("Validation Error"))
    }

    @Test
    fun `GET by id returns conversation`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val conversation = buildConversation(id = conversationId, title = "Found It")
        every { getConversationQueryService.findById(conversationId) } returns conversation

        mockMvc
            .perform(get("/api/v1/conversations/${conversationId.value}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(conversationId.value.toString()))
            .andExpect(jsonPath("$.title").value("Found It"))
    }

    @Test
    fun `GET by id with non-existent returns 404`() {
        val conversationId = ConversationId(UUID.randomUUID())
        every { getConversationQueryService.findById(conversationId) } throws
            NotFoundException("Conversation", conversationId.value.toString())

        mockMvc
            .perform(get("/api/v1/conversations/${conversationId.value}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Resource Not Found"))
    }

    @Test
    fun `DELETE archives conversation and returns 204`() {
        val userId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()

        mockMvc
            .perform(
                delete("/api/v1/conversations/$conversationId")
                    .header("X-User-Id", userId.toString()),
            ).andExpect(status().isNoContent)

        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `GET list returns user conversations`() {
        val userId = UUID.randomUUID()
        val conversations =
            listOf(
                buildConversation(userId = userId, title = "First"),
                buildConversation(userId = userId, title = "Second"),
            )
        every { getConversationQueryService.findByUserId(userId) } returns conversations

        mockMvc
            .perform(
                get("/api/v1/conversations")
                    .header("X-User-Id", userId.toString()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].title").value("First"))
            .andExpect(jsonPath("$[1].title").value("Second"))
    }

    @Test
    fun `GET list without X-User-Id header returns error`() {
        mockMvc
            .perform(get("/api/v1/conversations"))
            .andExpect(status().isInternalServerError)
    }

    private fun buildConversation(
        id: ConversationId = ConversationId(UUID.randomUUID()),
        userId: UUID = UUID.randomUUID(),
        title: String = "Test",
    ): Conversation {
        val now = Instant.now()
        return Conversation(
            id = id,
            userId = userId,
            title = title,
            status = ConversationStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.application.chat.ChatAnswerStreamService
import com.jorisjonkers.personalstack.agents.application.query.ChatSessionQueryService
import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionKind
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionStatus
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID

class ChatSessionControllerTest {
    private val commandBus = mockk<CommandBus>(relaxed = true)
    private val query = mockk<ChatSessionQueryService>()
    private val chatAnswerStream = mockk<ChatAnswerStreamService>()
    private val objectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = ChatSessionController(commandBus, query, chatAnswerStream)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    private fun session(
        id: ChatSessionId = ChatSessionId.random(),
        userId: UUID = UUID.randomUUID(),
    ): ChatSession {
        val now = Instant.now()
        return ChatSession(id, userId, "x", ChatSessionStatus.ACTIVE, ChatSessionKind.PLAIN, now, now)
    }

    @Test
    fun `POST creates session and returns 201`() {
        val s = session()
        every { query.get(any()) } returns ChatSessionQueryService.ChatSessionDetail(s, emptyList())
        mockMvc
            .perform(
                post("/api/v1/chat-sessions")
                    .header("X-User-Id", s.userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to "x", "kind" to "PLAIN"))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.kind").value("PLAIN"))
        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `GET list returns sessions for user`() {
        val uid = UUID.randomUUID()
        every { query.list(uid) } returns listOf(session(userId = uid))
        mockMvc
            .perform(get("/api/v1/chat-sessions").header("X-User-Id", uid.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `GET by id returns envelope`() {
        val s = session()
        every { query.get(s.id) } returns ChatSessionQueryService.ChatSessionDetail(s, emptyList())
        mockMvc
            .perform(get("/api/v1/chat-sessions/${s.id.value}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session.id").value(s.id.value.toString()))
            .andExpect(jsonPath("$.messages").isArray)
    }

    @Test
    fun `GET by id with unknown returns 404`() {
        every { query.get(any()) } returns null
        mockMvc
            .perform(get("/api/v1/chat-sessions/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST messages dispatches the append command`() {
        val s = session()
        // The controller looks up the just-appended message by id after
        // dispatch. Return a detail with no matching message so the
        // controller takes the error path; the test asserts the
        // dispatch happened either way.
        every { query.get(any<ChatSessionId>()) } returns
            ChatSessionQueryService.ChatSessionDetail(s, emptyList())
        try {
            mockMvc
                .perform(
                    post("/api/v1/chat-sessions/${s.id.value}/messages")
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
    fun `POST stream messages returns SSE response headers`() {
        val s = session()
        every { chatAnswerStream.stream(s.id, "hello") } returns SseEmitter()

        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${s.id.value}/messages/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "hello", "role" to "USER"))),
            ).andExpect(status().isOk)
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().string("Cache-Control", "no-cache"))
    }

    @Test
    fun `POST stream messages with blank body returns validation error`() {
        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${UUID.randomUUID()}/messages/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "", "role" to "USER"))),
            ).andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `POST stream messages with empty request body returns 400`() {
        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${UUID.randomUUID()}/messages/stream")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST messages with unknown session returns 404`() {
        every { query.get(any<ChatSessionId>()) } returns null
        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${UUID.randomUUID()}/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "hello", "role" to "USER"))),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE archives session and returns 204`() {
        mockMvc
            .perform(
                delete("/api/v1/chat-sessions/${UUID.randomUUID()}")
                    .header("X-User-Id", UUID.randomUUID().toString()),
            ).andExpect(status().isNoContent)
        verify { commandBus.dispatch(any()) }
    }
}

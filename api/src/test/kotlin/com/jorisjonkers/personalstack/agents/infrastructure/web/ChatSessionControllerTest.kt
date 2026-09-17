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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID

// This is the deprecated /api/v1/chat-sessions alias -- see
// ConversationControllerTest for the canonical /api/v1/conversations
// coverage. Kept to prove the alias still serves the pre-rename shapes
// byte-for-byte, and inherits the same ownership check (see #80).
@Suppress("DEPRECATION")
class ChatSessionControllerTest {
    private val commandBus = mockk<CommandBus>(relaxed = true)
    private val query = mockk<ConversationQueryService>()
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

    private fun conversation(
        id: ConversationId = ConversationId.random(),
        userId: UUID = UUID.randomUUID(),
    ): Conversation {
        val now = Instant.now()
        return Conversation(id, userId, "x", ConversationStatus.ACTIVE, ConversationKind.PLAIN, now, now)
    }

    @Test
    fun `POST creates session and returns 201`() {
        val c = conversation()
        every { query.get(any(), c.userId) } returns ConversationQueryService.ConversationDetail(c, emptyList())
        mockMvc
            .perform(
                post("/api/v1/chat-sessions")
                    .header("X-User-Id", c.userId.toString())
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
        every { query.list(uid) } returns listOf(conversation(userId = uid))
        mockMvc
            .perform(get("/api/v1/chat-sessions").header("X-User-Id", uid.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `GET by id returns envelope for the owner`() {
        val c = conversation()
        every { query.get(c.id, c.userId) } returns ConversationQueryService.ConversationDetail(c, emptyList())
        mockMvc
            .perform(get("/api/v1/chat-sessions/${c.id.value}").header("X-User-Id", c.userId.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session.id").value(c.id.value.toString()))
            .andExpect(jsonPath("$.messages").isArray)
    }

    @Test
    fun `GET by id with unknown session returns 404`() {
        val requester = UUID.randomUUID()
        every { query.get(any(), requester) } returns null
        mockMvc
            .perform(get("/api/v1/chat-sessions/${UUID.randomUUID()}").header("X-User-Id", requester.toString()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET by id owned by another user returns 404, indistinguishable from unknown`() {
        val c = conversation()
        val otherUser = UUID.randomUUID()
        every { query.get(c.id, otherUser) } returns null
        mockMvc
            .perform(get("/api/v1/chat-sessions/${c.id.value}").header("X-User-Id", otherUser.toString()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST messages dispatches the append command for the owner`() {
        val c = conversation()
        // The controller looks up the just-appended message by id after
        // dispatch. Return a detail with no matching message so the
        // controller takes the error path; the test asserts the
        // dispatch happened either way.
        every { query.get(c.id, c.userId) } returns
            ConversationQueryService.ConversationDetail(c, emptyList())
        try {
            mockMvc
                .perform(
                    post("/api/v1/chat-sessions/${c.id.value}/messages")
                        .header("X-User-Id", c.userId.toString())
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
    fun `POST messages with unknown session returns 404 and appends no Turn`() {
        val requester = UUID.randomUUID()
        every { query.get(any(), requester) } returns null
        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${UUID.randomUUID()}/messages")
                    .header("X-User-Id", requester.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "hello", "role" to "USER"))),
            ).andExpect(status().isNotFound)
        verify(exactly = 0) { commandBus.dispatch(any()) }
    }

    @Test
    fun `POST messages owned by another user returns 404 and appends no Turn`() {
        val c = conversation()
        val otherUser = UUID.randomUUID()
        every { query.get(c.id, otherUser) } returns null
        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${c.id.value}/messages")
                    .header("X-User-Id", otherUser.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "hello", "role" to "USER"))),
            ).andExpect(status().isNotFound)
        verify(exactly = 0) { commandBus.dispatch(any()) }
    }

    @Test
    fun `POST stream messages returns SSE response headers for the owner`() {
        val c = conversation()
        every { query.get(c.id, c.userId) } returns ConversationQueryService.ConversationDetail(c, emptyList())
        every { chatAnswerStream.stream(c.id, "hello") } returns SseEmitter()

        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${c.id.value}/messages/stream")
                    .header("X-User-Id", c.userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "hello", "role" to "USER"))),
            ).andExpect(status().isOk)
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().string("Cache-Control", "no-cache"))

        verify { chatAnswerStream.stream(c.id, "hello") }
    }

    @Test
    fun `POST stream messages with blank body returns validation error`() {
        val requester = UUID.randomUUID()
        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${UUID.randomUUID()}/messages/stream")
                    .header("X-User-Id", requester.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "", "role" to "USER"))),
            ).andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `POST stream messages with empty request body returns 400`() {
        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${UUID.randomUUID()}/messages/stream")
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST stream messages with unknown session returns 404 and never starts a stream`() {
        val requester = UUID.randomUUID()
        every { query.get(any(), requester) } returns null
        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${UUID.randomUUID()}/messages/stream")
                    .header("X-User-Id", requester.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "hello", "role" to "USER"))),
            ).andExpect(status().isNotFound)
        verify(exactly = 0) { chatAnswerStream.stream(any(), any()) }
    }

    @Test
    fun `POST stream messages owned by another user returns 404 and never starts a stream`() {
        val c = conversation()
        val otherUser = UUID.randomUUID()
        every { query.get(c.id, otherUser) } returns null
        mockMvc
            .perform(
                post("/api/v1/chat-sessions/${c.id.value}/messages/stream")
                    .header("X-User-Id", otherUser.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("body" to "hello", "role" to "USER"))),
            ).andExpect(status().isNotFound)
        verify(exactly = 0) { chatAnswerStream.stream(any(), any()) }
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

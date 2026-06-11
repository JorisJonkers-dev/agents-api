package com.jorisjonkers.personalstack.agents.flow

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

class MessageFlowIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    @Test
    fun `send message and retrieve by conversation`() {
        val userId = UUID.randomUUID().toString()
        val conversationId = createConversation(userId, "Msg Flow Test")

        mockMvc
            .perform(
                post("/api/v1/conversations/$conversationId/messages")
                    .header("X-User-Id", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("content" to "First message"))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.content").value("First message"))

        mockMvc
            .perform(
                get("/api/v1/conversations/$conversationId/messages")
                    .header("X-User-Id", userId),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].content").value("First message"))
    }

    @Test
    fun `send message without X-User-Id returns 500`() {
        val userId = UUID.randomUUID().toString()
        val conversationId = createConversation(userId, "No Auth Msg")

        mockMvc
            .perform(
                post("/api/v1/conversations/$conversationId/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("content" to "Should fail"))),
            ).andExpect(status().isInternalServerError)
    }

    @Test
    fun `send message with blank content returns 422`() {
        val userId = UUID.randomUUID().toString()
        val conversationId = createConversation(userId, "Blank Msg")

        mockMvc
            .perform(
                post("/api/v1/conversations/$conversationId/messages")
                    .header("X-User-Id", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("content" to ""))),
            ).andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `messages are ordered by creation time`() {
        val userId = UUID.randomUUID().toString()
        val conversationId = createConversation(userId, "Ordered Msgs")

        mockMvc.perform(
            post("/api/v1/conversations/$conversationId/messages")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("content" to "First"))),
        )

        mockMvc.perform(
            post("/api/v1/conversations/$conversationId/messages")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("content" to "Second"))),
        )

        mockMvc
            .perform(
                get("/api/v1/conversations/$conversationId/messages")
                    .header("X-User-Id", userId),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].content").value("First"))
            .andExpect(jsonPath("$[1].content").value("Second"))
    }

    @Test
    fun `messages for different conversations are isolated`() {
        val userId = UUID.randomUUID().toString()
        val conv1 = createConversation(userId, "Conv1")
        val conv2 = createConversation(userId, "Conv2")

        mockMvc.perform(
            post("/api/v1/conversations/$conv1/messages")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("content" to "Conv1 message"))),
        )

        mockMvc.perform(
            post("/api/v1/conversations/$conv2/messages")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("content" to "Conv2 message"))),
        )

        mockMvc
            .perform(
                get("/api/v1/conversations/$conv1/messages")
                    .header("X-User-Id", userId),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].content").value("Conv1 message"))

        mockMvc
            .perform(
                get("/api/v1/conversations/$conv2/messages")
                    .header("X-User-Id", userId),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].content").value("Conv2 message"))
    }

    private fun createConversation(
        userId: String,
        title: String,
    ): String {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/conversations")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("title" to title))),
                ).andExpect(status().isCreated)
                .andReturn()

        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }
}

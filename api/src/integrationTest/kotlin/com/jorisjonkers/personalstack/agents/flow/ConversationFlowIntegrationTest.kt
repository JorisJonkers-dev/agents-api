package com.jorisjonkers.personalstack.agents.flow

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

class ConversationFlowIntegrationTest
    @Autowired
    constructor(
        private val webApplicationContext: WebApplicationContext,
    ) : IntegrationTestBase {
        private lateinit var mockMvc: MockMvc
        private val objectMapper = ObjectMapper()

        @BeforeEach
        fun setUp() {
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        }

        @Test
        fun createAndGetConversation() {
            val userId = UUID.randomUUID().toString()

            val result =
                mockMvc
                    .perform(
                        post("/api/v1/conversations")
                            .header("X-User-Id", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(mapOf("title" to "Integration Chat"))),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.title").value("Integration Chat"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andReturn()

            val id = objectMapper.readTree(result.response.contentAsString)["id"].asText()

            mockMvc
                .perform(get("/api/v1/conversations/$id").header("X-User-Id", userId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.title").value("Integration Chat"))
        }

        @Test
        fun createConversationWithoutXUserIdReturns400() {
            mockMvc
                .perform(
                    post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("title" to "No Auth"))),
                ).andExpect(status().isBadRequest)
        }

        @Test
        fun listConversationsReturnsOnlyUserSConversations() {
            val userId1 = UUID.randomUUID().toString()
            val userId2 = UUID.randomUUID().toString()

            mockMvc.perform(
                post("/api/v1/conversations")
                    .header("X-User-Id", userId1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to "User1 Chat"))),
            )

            mockMvc.perform(
                post("/api/v1/conversations")
                    .header("X-User-Id", userId2)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to "User2 Chat"))),
            )

            mockMvc
                .perform(get("/api/v1/conversations").header("X-User-Id", userId1))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("User1 Chat"))
        }

        @Test
        fun archiveConversationReturns204() {
            val userId = UUID.randomUUID().toString()

            val result =
                mockMvc
                    .perform(
                        post("/api/v1/conversations")
                            .header("X-User-Id", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(mapOf("title" to "To Archive"))),
                    ).andReturn()

            val id = objectMapper.readTree(result.response.contentAsString)["id"].asText()

            mockMvc
                .perform(delete("/api/v1/conversations/$id").header("X-User-Id", userId))
                .andExpect(status().isNoContent)
        }

        @Test
        fun archiveNonExistentConversationReturns404() {
            val userId = UUID.randomUUID().toString()
            val nonExistentId = UUID.randomUUID()

            mockMvc
                .perform(delete("/api/v1/conversations/$nonExistentId").header("X-User-Id", userId))
                .andExpect(status().isNotFound)
        }

        @Test
        fun getNonExistentConversationReturns404() {
            val userId = UUID.randomUUID().toString()
            val nonExistentId = UUID.randomUUID()

            mockMvc
                .perform(get("/api/v1/conversations/$nonExistentId").header("X-User-Id", userId))
                .andExpect(status().isNotFound)
        }

        @Test
        fun createConversationWithBlankTitleReturns422() {
            val userId = UUID.randomUUID().toString()

            mockMvc
                .perform(
                    post("/api/v1/conversations")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("title" to ""))),
                ).andExpect(status().isUnprocessableContent)
        }

        @Test
        fun sendAndRetrieveMessages() {
            val userId = UUID.randomUUID().toString()

            val convResult =
                mockMvc
                    .perform(
                        post("/api/v1/conversations")
                            .header("X-User-Id", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(mapOf("title" to "Message Test"))),
                    ).andExpect(status().isCreated)
                    .andReturn()

            val conversationId = objectMapper.readTree(convResult.response.contentAsString)["id"].asText()

            mockMvc
                .perform(
                    post("/api/v1/conversations/$conversationId/messages")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("content" to "Hello there"))),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.content").value("Hello there"))
                .andExpect(jsonPath("$.role").value("USER"))

            mockMvc
                .perform(
                    get("/api/v1/conversations/$conversationId/messages")
                        .header("X-User-Id", userId),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("Hello there"))
        }
    }

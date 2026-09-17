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

// Split out from AgentsApiContractIntegrationTest (which was already at
// detekt's 15-function ceiling): a second user must never read or append
// to the first user's Conversation, on the canonical path or the
// deprecated alias (#80).
class ConversationOwnershipIntegrationTest
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
        fun aSecondUserCannotReadOrAppendToTheFirstUsersConversation() {
            val ownerId = UUID.randomUUID().toString()
            val otherUserId = UUID.randomUUID().toString()
            val conversationId = createConversation("/api/v1/conversations", ownerId)

            mockMvc
                .perform(get("/api/v1/conversations/$conversationId").header("X-User-Id", otherUserId))
                .andExpect(status().isNotFound)

            mockMvc
                .perform(get("/api/v1/conversations/$conversationId/messages").header("X-User-Id", otherUserId))
                .andExpect(status().isNotFound)

            mockMvc
                .perform(
                    post("/api/v1/conversations/$conversationId/messages")
                        .header("X-User-Id", otherUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("body" to "intruder", "role" to "USER"))),
                ).andExpect(status().isNotFound)

            mockMvc
                .perform(
                    post("/api/v1/conversations/$conversationId/messages/stream")
                        .header("X-User-Id", otherUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("body" to "intruder", "role" to "USER"))),
                ).andExpect(status().isNotFound)

            mockMvc
                .perform(get("/api/v1/conversations/$conversationId").header("X-User-Id", ownerId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.messages").isEmpty)

            mockMvc
                .perform(get("/api/v1/conversations/$conversationId/messages").header("X-User-Id", ownerId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isEmpty)
        }

        @Test
        fun aSecondUserCannotReadOrAppendToTheFirstUsersChatSession() {
            val ownerId = UUID.randomUUID().toString()
            val otherUserId = UUID.randomUUID().toString()
            val sessionId = createConversation("/api/v1/chat-sessions", ownerId)

            mockMvc
                .perform(get("/api/v1/chat-sessions/$sessionId").header("X-User-Id", otherUserId))
                .andExpect(status().isNotFound)

            mockMvc
                .perform(
                    post("/api/v1/chat-sessions/$sessionId/messages")
                        .header("X-User-Id", otherUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("body" to "intruder", "role" to "USER"))),
                ).andExpect(status().isNotFound)

            mockMvc
                .perform(get("/api/v1/chat-sessions/$sessionId").header("X-User-Id", ownerId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.messages").isEmpty)
        }

        private fun createConversation(
            basePath: String,
            ownerId: String,
        ): String {
            val createResult =
                mockMvc
                    .perform(
                        post(basePath)
                            .header("X-User-Id", ownerId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(mapOf("title" to "private"))),
                    ).andExpect(status().isCreated)
                    .andReturn()
            return objectMapper.readTree(createResult.response.contentAsString)["id"].asText()
        }
    }

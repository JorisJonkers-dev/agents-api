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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

class AgentsApiContractIntegrationTest
    @Autowired
    constructor(
        private val webApplicationContext: WebApplicationContext,
    ) : IntegrationTestBase {
        private companion object {
            const val HTTP_NOT_FOUND = 404
        }

        private lateinit var mockMvc: MockMvc
        private val objectMapper = ObjectMapper()

        @BeforeEach
        fun setUp() {
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        }

        @Test
        fun openapiSpecEndpointReturnsValidJSON() {
            mockMvc
                .perform(get("/api/v1/api-docs"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info").exists())
                .andExpect(jsonPath("$.paths").exists())
        }

        @Test
        fun conversationCreationResponseMatchesExpectedSchema() {
            val userId = UUID.randomUUID().toString()

            mockMvc
                .perform(
                    post("/api/v1/conversations")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("title" to "Contract Test Chat"))),
                ).andExpect(status().isCreated)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.title").value("Contract Test Chat"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
        }

        @Test
        fun messageResponseMatchesExpectedSchema() {
            val userId = UUID.randomUUID().toString()

            val convResult =
                mockMvc
                    .perform(
                        post("/api/v1/conversations")
                            .header("X-User-Id", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(mapOf("title" to "Message Schema Test"))),
                    ).andExpect(status().isCreated)
                    .andReturn()

            val conversationId = objectMapper.readTree(convResult.response.contentAsString)["id"].asText()

            mockMvc
                .perform(
                    post("/api/v1/conversations/$conversationId/messages")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("content" to "Schema check"))),
                ).andExpect(status().isCreated)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.conversationId").exists())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.content").value("Schema check"))
                .andExpect(jsonPath("$.createdAt").exists())
        }

        @Test
        fun healthEndpointResponseMatchesSchema() {
            mockMvc
                .perform(get("/api/v1/health"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("agents-api"))
        }

        @Test
        fun repositoryCreationResponseMatchesExpectedSchema() {
            val unique = UUID.randomUUID().toString().take(8)
            mockMvc
                .perform(
                    post("/api/v1/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                mapOf(
                                    "name" to "repo-$unique",
                                    "repoUrl" to "git@github.com:owner/repo-$unique.git",
                                    "defaultBranch" to "main",
                                ),
                            ),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("repo-$unique"))
                .andExpect(jsonPath("$.repoUrl").value("git@github.com:owner/repo-$unique.git"))
                .andExpect(jsonPath("$.defaultBranch").value("main"))
        }

        @Test
        fun repositoryListResponseIsAJSONArray() {
            val unique = UUID.randomUUID().toString().take(8)
            mockMvc.perform(
                post("/api/v1/repositories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "name" to "list-$unique",
                                "repoUrl" to "git@github.com:o/list-$unique.git",
                                "defaultBranch" to "main",
                            ),
                        ),
                    ),
            )

            mockMvc
                .perform(get("/api/v1/repositories"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }

        @Test
        fun repositoryDetailReturnsAttachedProjectsArray() {
            val unique = UUID.randomUUID().toString().take(8)
            val createResult =
                mockMvc
                    .perform(
                        post("/api/v1/repositories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    mapOf(
                                        "name" to "detail-$unique",
                                        "repoUrl" to "git@github.com:o/detail-$unique.git",
                                        "defaultBranch" to "main",
                                    ),
                                ),
                            ),
                    ).andExpect(status().isCreated)
                    .andReturn()
            val repoId = objectMapper.readTree(createResult.response.contentAsString)["id"].asText()
            mockMvc
                .perform(get("/api/v1/repositories/$repoId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.repository.id").value(repoId))
                .andExpect(jsonPath("$.attachedProjects").isArray)
        }

        @Test
        fun linkingAndUnlinkingARepositoryToAProjectSurfacesInDetailResponses() {
            val unique = UUID.randomUUID().toString().take(8)
            val projectResult =
                mockMvc
                    .perform(
                        post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    mapOf("name" to "Project $unique", "slug" to "project-$unique"),
                                ),
                            ),
                    ).andExpect(status().isCreated)
                    .andReturn()
            val projectId = objectMapper.readTree(projectResult.response.contentAsString)["id"].asText()

            val repoResult =
                mockMvc
                    .perform(
                        post("/api/v1/repositories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    mapOf(
                                        "name" to "link-$unique",
                                        "repoUrl" to "git@github.com:o/link-$unique.git",
                                        "defaultBranch" to "main",
                                    ),
                                ),
                            ),
                    ).andExpect(status().isCreated)
                    .andReturn()
            val repoId = objectMapper.readTree(repoResult.response.contentAsString)["id"].asText()

            mockMvc
                .perform(
                    post("/api/v1/projects/$projectId/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("repositoryId" to repoId))),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$[0].id").value(repoId))

            mockMvc
                .perform(get("/api/v1/projects/$projectId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.repositories[0].id").value(repoId))

            mockMvc
                .perform(get("/api/v1/repositories/$repoId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.attachedProjects[0].id").value(projectId))

            mockMvc
                .perform(delete("/api/v1/projects/$projectId/repositories/$repoId"))
                .andExpect(status().isNoContent)
        }

        @Test
        fun creatingAWorkspaceWithAnUnknownRepositoryIdReturns404ProblemDetail() {
            val bogus = UUID.randomUUID()
            mockMvc
                .perform(
                    post("/api/v1/workspaces")
                        .header("X-User-Id", "contract-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                mapOf(
                                    "name" to "bogus-repo-workspace",
                                    "kind" to "REPO_BACKED",
                                    "repositoryId" to bogus.toString(),
                                ),
                            ),
                        ),
                ).andExpect(status().isNotFound)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(HTTP_NOT_FOUND))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(bogus.toString())))
        }

        @Test
        fun chatSessionCreationResponseMatchesExpectedSchema() {
            val userId = UUID.randomUUID().toString()
            mockMvc
                .perform(
                    post("/api/v1/chat-sessions")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("title" to "Demo chat"))),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.title").value("Demo chat"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
        }

        @Test
        fun chatSessionListEndpointReturnsArrayForUser() {
            val userId = UUID.randomUUID().toString()
            mockMvc.perform(
                post("/api/v1/chat-sessions")
                    .header("X-User-Id", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to "List me"))),
            )
            mockMvc
                .perform(get("/api/v1/chat-sessions").header("X-User-Id", userId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].status").exists())
        }

        @Test
        fun appendingAChatMessageReturnsTheMessageEnvelopeAndSurfacesInDetail() {
            val userId = UUID.randomUUID().toString()
            val createResult =
                mockMvc
                    .perform(
                        post("/api/v1/chat-sessions")
                            .header("X-User-Id", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(mapOf("title" to "msg-test"))),
                    ).andExpect(status().isCreated)
                    .andReturn()
            val sessionId = objectMapper.readTree(createResult.response.contentAsString)["id"].asText()

            mockMvc
                .perform(
                    post("/api/v1/chat-sessions/$sessionId/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                mapOf("body" to "hello world", "role" to "USER"),
                            ),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.body").value("hello world"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.sessionId").value(sessionId))

            mockMvc
                .perform(get("/api/v1/chat-sessions/$sessionId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.session.id").value(sessionId))
                .andExpect(jsonPath("$.messages[0].body").value("hello world"))
        }

        @Test
        fun conversationListResponseIsValidJSONArray() {
            val userId = UUID.randomUUID().toString()

            // Create a conversation so the list is non-empty
            mockMvc.perform(
                post("/api/v1/conversations")
                    .header("X-User-Id", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to "List Test Chat"))),
            )

            mockMvc
                .perform(get("/api/v1/conversations").header("X-User-Id", userId))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray)
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].title").exists())
                .andExpect(jsonPath("$[0].status").exists())
        }
    }

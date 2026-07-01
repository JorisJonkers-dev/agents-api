package com.jorisjonkers.personalstack.agents.flow

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
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

@ActiveProfiles("system-test")
class WorkspaceRepositoryFlowIntegrationTest
    @Autowired
    constructor(
        private val webApplicationContext: WebApplicationContext,
    ) : IntegrationTestBase {
        private companion object {
            const val HTTP_BAD_REQUEST = 400
            const val UNIQUE_SUFFIX_LENGTH = 8
        }

        private lateinit var mockMvc: MockMvc
        private val objectMapper = ObjectMapper()

        @BeforeEach
        fun setUp() {
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        }

        @Test
        fun projectWorkspaceCreationSurfacesPrimaryAndProjectRepositoryExtrasInDetail() {
            val unique = unique()
            val projectId = createProject(unique)
            val primaryId = createRepository("primary-$unique")
            val extraOneId = createRepository("extra-one-$unique")
            val extraTwoId = createRepository("extra-two-$unique")
            linkRepository(projectId, extraOneId)
            linkRepository(projectId, extraTwoId)

            val workspaceId =
                createWorkspace(
                    name = "workspace-$unique",
                    projectId = projectId,
                    repositoryId = primaryId,
                )

            val detail = getWorkspaceDetail(workspaceId)
            val repositories = detail["workspace"]["repositories"]
            assertThat(repositories.map { it["id"].asText() })
                .containsExactlyInAnyOrder(primaryId, extraOneId, extraTwoId)
            assertThat(repositories.map { it["name"].asText() })
                .containsExactlyInAnyOrder("primary-$unique", "extra-one-$unique", "extra-two-$unique")
        }

        @Test
        fun workspaceCreationAcceptsMultiRepositorySelectionWithExplicitPrimary() {
            val unique = unique()
            val primaryId = createRepository("primary-$unique")
            val extraOneId = createRepository("extra-one-$unique")
            val extraTwoId = createRepository("extra-two-$unique")

            val result =
                mockMvc
                    .perform(
                        post("/api/v1/workspaces")
                            .header("X-User-Id", "flow-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    mapOf(
                                        "name" to "workspace-$unique",
                                        "kind" to "REPO_BACKED",
                                        "primaryRepositoryId" to primaryId,
                                        "repositoryIds" to listOf(extraOneId, primaryId, extraTwoId),
                                    ),
                                ),
                            ),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.repositoryId").value(primaryId))
                    .andReturn()
            val workspaceId = objectMapper.readTree(result.response.contentAsString)["id"].asText()

            val repositories = getWorkspaceDetail(workspaceId)["workspace"]["repositories"]
            assertThat(repositories.map { it["id"].asText() })
                .containsExactlyInAnyOrder(primaryId, extraOneId, extraTwoId)
            assertThat(repositories.single { it["isPrimary"].asBoolean() }["id"].asText())
                .isEqualTo(primaryId)
        }

        @Test
        fun attachAndDetachEndpointsMutateWorkspaceDetailRepositories() {
            val unique = unique()
            val primaryId = createRepository("primary-$unique")
            val extraId = createRepository("extra-$unique")
            val workspaceId = createWorkspace(name = "workspace-$unique", repositoryId = primaryId)

            assertWorkspaceRepositories(workspaceId, primaryId)

            mockMvc
                .perform(
                    post("/api/v1/workspaces/$workspaceId/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("repositoryId" to extraId))),
                ).andExpect(status().isNoContent)

            assertWorkspaceRepositories(workspaceId, primaryId, extraId)

            mockMvc
                .perform(delete("/api/v1/workspaces/$workspaceId/repositories/$extraId"))
                .andExpect(status().isNoContent)

            assertWorkspaceRepositories(workspaceId, primaryId)
        }

        @Test
        fun detachingThePrimaryRepositoryReturnsTheValidationError() {
            val unique = unique()
            val primaryId = createRepository("primary-$unique")
            val workspaceId = createWorkspace(name = "workspace-$unique", repositoryId = primaryId)

            mockMvc
                .perform(delete("/api/v1/workspaces/$workspaceId/repositories/$primaryId"))
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(HTTP_BAD_REQUEST))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("cannot detach the primary repository from a workspace"))

            assertWorkspaceRepositories(workspaceId, primaryId)
        }

        private fun createProject(unique: String): String {
            val result =
                mockMvc
                    .perform(
                        post("/api/v1/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    mapOf(
                                        "name" to "Project $unique",
                                        "slug" to "project-$unique",
                                    ),
                                ),
                            ),
                    ).andExpect(status().isCreated)
                    .andReturn()
            return objectMapper.readTree(result.response.contentAsString)["id"].asText()
        }

        private fun createRepository(name: String): String {
            val result =
                mockMvc
                    .perform(
                        post("/api/v1/repositories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    mapOf(
                                        "name" to name,
                                        "repoUrl" to "git@github.com:o/$name.git",
                                        "defaultBranch" to "main",
                                    ),
                                ),
                            ),
                    ).andExpect(status().isCreated)
                    .andReturn()
            return objectMapper.readTree(result.response.contentAsString)["id"].asText()
        }

        private fun linkRepository(
            projectId: String,
            repositoryId: String,
        ) {
            mockMvc
                .perform(
                    post("/api/v1/projects/$projectId/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("repositoryId" to repositoryId))),
                ).andExpect(status().isCreated)
        }

        private fun createWorkspace(
            name: String,
            repositoryId: String,
            projectId: String? = null,
        ): String {
            val body =
                mutableMapOf<String, String>(
                    "name" to name,
                    "kind" to "REPO_BACKED",
                    "repositoryId" to repositoryId,
                )
            if (projectId != null) {
                body["projectId"] = projectId
            }
            val result =
                mockMvc
                    .perform(
                        post("/api/v1/workspaces")
                            .header("X-User-Id", "flow-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.repositoryId").value(repositoryId))
                    .andReturn()
            return objectMapper.readTree(result.response.contentAsString)["id"].asText()
        }

        private fun getWorkspaceDetail(workspaceId: String) =
            mockMvc
                .perform(get("/api/v1/workspaces/$workspaceId"))
                .andExpect(status().isOk)
                .andReturn()
                .let { objectMapper.readTree(it.response.contentAsString) }

        private fun assertWorkspaceRepositories(
            workspaceId: String,
            vararg expectedRepositoryIds: String,
        ) {
            val repositoryIds =
                getWorkspaceDetail(workspaceId)["workspace"]["repositories"]
                    .map { it["id"].asText() }
            assertThat(repositoryIds).containsExactlyInAnyOrder(*expectedRepositoryIds)
        }

        private fun unique(): String = UUID.randomUUID().toString().take(UNIQUE_SUFFIX_LENGTH)
    }

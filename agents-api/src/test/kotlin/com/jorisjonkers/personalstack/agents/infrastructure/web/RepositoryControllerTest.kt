package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.application.RepositoryVerificationService
import com.jorisjonkers.personalstack.agents.application.query.RepositoryQueryService
import com.jorisjonkers.personalstack.agents.domain.model.AccessVerification
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
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

class RepositoryControllerTest {
    private val commandBus = mockk<CommandBus>(relaxed = true)
    private val query = mockk<RepositoryQueryService>()
    private val verificationService = mockk<RepositoryVerificationService>()
    private val objectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = RepositoryController(commandBus, query, verificationService)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    private fun repo(
        id: RepositoryId = RepositoryId.random(),
        verification: AccessVerification? = null,
    ) = Repository(
        id = id,
        name = "agents",
        repoUrl = "git@github.com:o/r.git",
        defaultBranch = "main",
        vaultKeyPath = "x",
        deployKeyFingerprint = null,
        deployKeyAddedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        verification = verification,
    )

    @Test
    fun `POST creates repository and returns 201`() {
        every { query.get(any()) } returns RepositoryQueryService.RepositoryDetail(repo(), emptyList())
        mockMvc
            .perform(
                post("/api/v1/repositories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "name" to "agents",
                                "repoUrl" to "git@github.com:o/r.git",
                                "defaultBranch" to "main",
                            ),
                        ),
                    ),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("agents"))
        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `POST with blank name returns 422`() {
        mockMvc
            .perform(
                post("/api/v1/repositories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("name" to "", "repoUrl" to "x", "defaultBranch" to "main"),
                        ),
                    ),
            ).andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `GET list returns array`() {
        every { query.list() } returns listOf(repo(), repo())
        mockMvc
            .perform(get("/api/v1/repositories"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET by id returns detail envelope`() {
        val r = repo()
        every { query.get(r.id) } returns RepositoryQueryService.RepositoryDetail(r, emptyList())
        mockMvc
            .perform(get("/api/v1/repositories/${r.id.value}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.repository.id").value(r.id.value.toString()))
            .andExpect(jsonPath("$.attachedProjects").isArray)
    }

    @Test
    fun `GET by id exposes the stored verification result`() {
        val r =
            repo(
                verification =
                    AccessVerification(
                        read = true,
                        write = false,
                        defaultBranchProtected = false,
                        checkedAt = Instant.now(),
                        messages = listOf("deploy key is read-only"),
                    ),
            )
        every { query.get(r.id) } returns RepositoryQueryService.RepositoryDetail(r, emptyList())
        mockMvc
            .perform(get("/api/v1/repositories/${r.id.value}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.repository.verification.read").value(true))
            .andExpect(jsonPath("$.repository.verification.write").value(false))
            .andExpect(jsonPath("$.repository.verification.defaultBranchProtected").value(false))
            .andExpect(jsonPath("$.repository.verification.messages[0]").value("deploy key is read-only"))
    }

    @Test
    fun `POST verify re-runs the check and returns the refreshed repository`() {
        val r =
            repo(
                verification =
                    AccessVerification(
                        read = true,
                        write = true,
                        defaultBranchProtected = true,
                        checkedAt = Instant.now(),
                        messages = emptyList(),
                    ),
            )
        every { verificationService.reverify(r.id) } returns r
        mockMvc
            .perform(post("/api/v1/repositories/${r.id.value}/verify"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verification.read").value(true))
            .andExpect(jsonPath("$.verification.write").value(true))
        verify { verificationService.reverify(r.id) }
    }

    @Test
    fun `POST verify on unknown id returns 404`() {
        every { verificationService.reverify(any()) } returns null
        mockMvc
            .perform(post("/api/v1/repositories/${UUID.randomUUID()}/verify"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET by id with unknown id returns 404`() {
        every { query.get(any()) } returns null
        mockMvc
            .perform(get("/api/v1/repositories/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST attach key returns 202`() {
        mockMvc
            .perform(
                post("/api/v1/repositories/${UUID.randomUUID()}/key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "privateKeyOpenssh" to
                                    "-----BEGIN OPENSSH PRIVATE KEY-----\nx\n-----END OPENSSH PRIVATE KEY-----",
                                "publicKeyOpenssh" to "ssh-ed25519 AAAAxxx me",
                                "knownHosts" to null,
                            ),
                        ),
                    ),
            ).andExpect(status().isAccepted)
        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `DELETE returns 204`() {
        mockMvc
            .perform(delete("/api/v1/repositories/${UUID.randomUUID()}"))
            .andExpect(status().isNoContent)
        verify { commandBus.dispatch(any()) }
    }
}

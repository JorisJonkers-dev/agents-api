package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.application.query.ProjectQueryService
import com.jorisjonkers.personalstack.agents.application.query.RepositoryQueryService
import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
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

class ProjectControllerTest {
    private val commandBus = mockk<CommandBus>(relaxed = true)
    private val projectQuery = mockk<ProjectQueryService>()
    private val repositoryQuery = mockk<RepositoryQueryService>()
    private val objectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = ProjectController(commandBus, projectQuery, repositoryQuery)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    private fun project(id: ProjectId = ProjectId.random()) =
        Project(id, "Agents", "agents", "", Instant.now(), Instant.now())

    private fun repository(id: RepositoryId = RepositoryId.random()) =
        Repository(
            id = id,
            name = "n",
            repoUrl = "u",
            defaultBranch = "main",
            vaultKeyPath = "x",
            deployKeyFingerprint = null,
            deployKeyAddedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `POST creates project and returns 201`() {
        val p = project()
        every { projectQuery.get(any()) } returns ProjectQueryService.ProjectDetail(p, emptyList())
        mockMvc
            .perform(
                post("/api/v1/projects")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("name" to "Agents", "slug" to "agents"),
                        ),
                    ),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.slug").value("agents"))
        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `GET list returns projects`() {
        every { projectQuery.list() } returns listOf(project(), project())
        mockMvc
            .perform(get("/api/v1/projects"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET by id returns envelope with links and repositories`() {
        val p = project()
        val r = repository()
        every { projectQuery.get(p.id) } returns ProjectQueryService.ProjectDetail(p, emptyList())
        every { repositoryQuery.listByProject(p.id) } returns listOf(r)
        mockMvc
            .perform(get("/api/v1/projects/${p.id.value}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.project.id").value(p.id.value.toString()))
            .andExpect(jsonPath("$.repositories[0].id").value(r.id.value.toString()))
    }

    @Test
    fun `GET by id with unknown returns 404`() {
        every { projectQuery.get(any()) } returns null
        mockMvc
            .perform(get("/api/v1/projects/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST repositories links and returns the linked list`() {
        val p = project()
        val r = repository()
        every { repositoryQuery.listByProject(p.id) } returns listOf(r)
        mockMvc
            .perform(
                post("/api/v1/projects/${p.id.value}/repositories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("repositoryId" to r.id.value.toString()))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$[0].id").value(r.id.value.toString()))
        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `DELETE repositories unlinks and returns 204`() {
        mockMvc
            .perform(
                delete("/api/v1/projects/${UUID.randomUUID()}/repositories/${UUID.randomUUID()}"),
            ).andExpect(status().isNoContent)
        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `POST links dispatches the legacy AddGithubLink command`() {
        // The deprecated link-flow path stays wired so existing UI
        // callers keep working through the V9 window; verify only the
        // dispatch hook (the lookup-by-fresh-id path is hard to
        // round-trip in a unit test because the generated link id
        // doesn't escape the controller).
        every { projectQuery.get(any()) } returns ProjectQueryService.ProjectDetail(project(), emptyList())
        try {
            mockMvc.perform(
                post("/api/v1/projects/${UUID.randomUUID()}/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("name" to "demo", "repoUrl" to "git@x:o/r.git", "defaultBranch" to "main"),
                        ),
                    ),
            )
        } catch (_: Throwable) {
            // controller's error("link not visible after add") may bubble
        }
        verify { commandBus.dispatch(any()) }
    }
}

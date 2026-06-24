package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class LinkRepositoryToProjectCommandHandlerTest {
    private val projects = mockk<ProjectsRepository>()
    private val repositories = mockk<RepositoryRepository>()
    private val junction = mockk<ProjectRepositoryRepository>()
    private val handler = LinkRepositoryToProjectCommandHandler(projects, repositories, junction)

    private fun project(id: ProjectId = ProjectId.random()) =
        Project(id, "name", "slug", "", Instant.now(), Instant.now())

    private fun repository(id: RepositoryId = RepositoryId.random()) =
        Repository(
            id = id,
            name = "name",
            repoUrl = "url",
            defaultBranch = "main",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `handle inserts a junction row when both ends exist`() {
        val p = project()
        val r = repository()
        every { projects.findById(p.id) } returns p
        every { repositories.findById(r.id) } returns r
        every { junction.link(p.id, r.id) } returns
            ProjectRepositoryRepository.Link(p.id, r.id, Instant.now())

        handler.handle(LinkRepositoryToProjectCommand(p.id, r.id))

        verify { junction.link(p.id, r.id) }
    }

    @Test
    fun `handle errors on missing project`() {
        val r = repository()
        every { projects.findById(any()) } returns null
        assertThrows<IllegalStateException> {
            handler.handle(LinkRepositoryToProjectCommand(ProjectId.random(), r.id))
        }
    }

    @Test
    fun `handle errors on missing repository`() {
        val p = project()
        every { projects.findById(p.id) } returns p
        every { repositories.findById(any()) } returns null
        assertThrows<IllegalStateException> {
            handler.handle(LinkRepositoryToProjectCommand(p.id, RepositoryId.random()))
        }
    }
}

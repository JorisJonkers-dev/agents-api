package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class CreateProjectCommandHandlerTest {
    private val projects = mockk<ProjectsRepository>()
    private val handler = CreateProjectCommandHandler(projects)

    @Test
    fun `handle persists a project with normalised name`() {
        every { projects.findBySlug(any()) } returns null
        val saved = slot<Project>()
        every { projects.save(capture(saved)) } answers { saved.captured }

        handler.handle(
            CreateProjectCommand(
                projectId = ProjectId.random(),
                name = "  Agents  ",
                slug = "agents",
                description = "  monorepo  ",
            ),
        )

        assertThat(saved.captured.name).isEqualTo("Agents")
        assertThat(saved.captured.slug).isEqualTo("agents")
        assertThat(saved.captured.description).isEqualTo("monorepo")
        verify { projects.save(any()) }
    }

    @Test
    fun `handle rejects blank name`() {
        every { projects.findBySlug(any()) } returns null
        assertThrows<IllegalArgumentException> {
            handler.handle(CreateProjectCommand(ProjectId.random(), "  ", "abc"))
        }
    }

    @Test
    fun `handle rejects invalid slug`() {
        every { projects.findBySlug(any()) } returns null
        assertThrows<IllegalArgumentException> {
            handler.handle(CreateProjectCommand(ProjectId.random(), "X", "NOT lowercase"))
        }
    }

    @Test
    fun `handle rejects slug already in use by another project`() {
        val existing =
            Project(
                id = ProjectId.random(),
                name = "other",
                slug = "shared",
                description = "",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { projects.findBySlug("shared") } returns existing
        assertThrows<IllegalStateException> {
            handler.handle(CreateProjectCommand(ProjectId.random(), "Mine", "shared"))
        }
    }
}

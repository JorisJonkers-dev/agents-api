package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class AddGithubLinkCommandHandlerTest {
    private val projects = mockk<ProjectsRepository>()
    private val links = mockk<GithubLinkRepository>()
    private val handler = AddGithubLinkCommandHandler(projects, links)

    private fun project() =
        Project(
            id = ProjectId.random(),
            name = "Agents",
            slug = "agents",
            description = "",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `handle creates link with pre-allocated vault path and no fingerprint yet`() {
        val proj = project()
        every { projects.findById(proj.id) } returns proj
        val saved = slot<GithubLink>()
        every { links.save(capture(saved)) } answers { saved.captured }

        val linkId = GithubLinkId.random()
        handler.handle(
            AddGithubLinkCommand(
                linkId = linkId,
                projectId = proj.id,
                name = "  agents  ",
                repoUrl = "git@github.com:owner/repo.git",
                defaultBranch = "",
            ),
        )

        assertThat(saved.captured.id).isEqualTo(linkId)
        assertThat(saved.captured.name).isEqualTo("agents")
        assertThat(saved.captured.defaultBranch).isEqualTo("main")
        assertThat(saved.captured.vaultKeyPath).isEqualTo("secret/data/agents/projects/${proj.id}/repos/$linkId")
        assertThat(saved.captured.deployKeyFingerprint).isNull()
        assertThat(saved.captured.deployKeyAddedAt).isNull()
    }

    @Test
    fun `handle errors on unknown project`() {
        val missing = ProjectId.random()
        every { projects.findById(missing) } returns null
        assertThrows<IllegalStateException> {
            handler.handle(
                AddGithubLinkCommand(GithubLinkId.random(), missing, "x", "git@github.com:o/r.git"),
            )
        }
    }

    @Test
    fun `handle rejects blank name`() {
        val proj = project()
        every { projects.findById(proj.id) } returns proj
        assertThrows<IllegalArgumentException> {
            handler.handle(AddGithubLinkCommand(GithubLinkId.random(), proj.id, "   ", "git@x:o/r.git"))
        }
    }

    @Test
    fun `handle rejects blank repoUrl`() {
        val proj = project()
        every { projects.findById(proj.id) } returns proj
        assertThrows<IllegalArgumentException> {
            handler.handle(AddGithubLinkCommand(GithubLinkId.random(), proj.id, "x", ""))
        }
    }
}

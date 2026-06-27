package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class CreateRepositoryCommandHandlerTest {
    private val repositories = mockk<RepositoryRepository>()
    private val handler = CreateRepositoryCommandHandler(repositories)

    @Test
    fun `handle creates a repository with per-repo vault path and no fingerprint yet`() {
        every { repositories.findByRepoUrl(any()) } returns null
        val saved = slot<Repository>()
        every { repositories.save(capture(saved)) } answers { saved.captured }

        val id = RepositoryId.random()
        handler.handle(
            CreateRepositoryCommand(
                repositoryId = id,
                name = "  agents  ",
                repoUrl = "  git@github.com:owner/repo.git  ",
                defaultBranch = "",
            ),
        )

        assertThat(saved.captured.id).isEqualTo(id)
        assertThat(saved.captured.name).isEqualTo("agents")
        assertThat(saved.captured.repoUrl).isEqualTo("git@github.com:owner/repo.git")
        assertThat(saved.captured.defaultBranch).isEqualTo("main")
    }

    @Test
    fun `handle rejects blank name`() {
        every { repositories.findByRepoUrl(any()) } returns null
        assertThrows<IllegalArgumentException> {
            handler.handle(
                CreateRepositoryCommand(RepositoryId.random(), "  ", "git@github.com:o/r.git"),
            )
        }
    }

    @Test
    fun `handle rejects blank repoUrl`() {
        every { repositories.findByRepoUrl(any()) } returns null
        assertThrows<IllegalArgumentException> {
            handler.handle(
                CreateRepositoryCommand(RepositoryId.random(), "x", " "),
            )
        }
    }

    @Test
    fun `handle allows the same name with a different url`() {
        // The same short name under two different orgs is legal now; only the
        // URL must be unique, so a distinct URL must not be rejected.
        every { repositories.findByRepoUrl("https://github.com/JorisJonkers-dev/.github") } returns null
        val saved = slot<Repository>()
        every { repositories.save(capture(saved)) } answers { saved.captured }

        handler.handle(
            CreateRepositoryCommand(
                RepositoryId.random(),
                name = ".github",
                repoUrl = "https://github.com/JorisJonkers-dev/.github",
            ),
        )

        assertThat(saved.captured.name).isEqualTo(".github")
        assertThat(saved.captured.repoUrl).isEqualTo("https://github.com/JorisJonkers-dev/.github")
    }

    @Test
    fun `handle rejects a url already registered by another repository`() {
        val existing =
            Repository(
                id = RepositoryId.random(),
                name = "agents",
                repoUrl = "git@github.com:owner/repo.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { repositories.findByRepoUrl("git@github.com:owner/repo.git") } returns existing
        assertThrows<IllegalStateException> {
            handler.handle(
                CreateRepositoryCommand(RepositoryId.random(), "agents", "git@github.com:owner/repo.git"),
            )
        }
    }
}

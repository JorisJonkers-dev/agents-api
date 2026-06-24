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
        every { repositories.findByName(any()) } returns null
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
        every { repositories.findByName(any()) } returns null
        assertThrows<IllegalArgumentException> {
            handler.handle(
                CreateRepositoryCommand(RepositoryId.random(), "  ", "git@github.com:o/r.git"),
            )
        }
    }

    @Test
    fun `handle rejects blank repoUrl`() {
        every { repositories.findByName(any()) } returns null
        assertThrows<IllegalArgumentException> {
            handler.handle(
                CreateRepositoryCommand(RepositoryId.random(), "x", " "),
            )
        }
    }

    @Test
    fun `handle rejects name already in use by another repository`() {
        val existing =
            Repository(
                id = RepositoryId.random(),
                name = "agents",
                repoUrl = "git@github.com:other/repo.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { repositories.findByName("agents") } returns existing
        assertThrows<IllegalStateException> {
            handler.handle(
                CreateRepositoryCommand(RepositoryId.random(), "agents", "git@github.com:owner/repo.git"),
            )
        }
    }
}

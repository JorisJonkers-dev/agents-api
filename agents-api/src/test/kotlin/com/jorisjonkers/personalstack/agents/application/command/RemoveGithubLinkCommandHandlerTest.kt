package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.common.vault.VaultKeyValueWriter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant

class RemoveGithubLinkCommandHandlerTest {
    private val links = mockk<GithubLinkRepository>(relaxed = true)
    private val vaultWriter = mockk<VaultKeyValueWriter>(relaxed = true)
    private val vaultWriterProvider =
        mockk<org.springframework.beans.factory.ObjectProvider<VaultKeyValueWriter>> {
            every { ifAvailable } returns vaultWriter
        }
    private val handler = RemoveGithubLinkCommandHandler(links, vaultWriterProvider)

    @Test
    fun `handle best-effort removes any leftover vault secret then deletes the link row`() {
        val link =
            GithubLink(
                id = GithubLinkId.random(),
                projectId = ProjectId.random(),
                name = "x",
                repoUrl = "git@github.com:o/r.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { links.findById(link.id) } returns link

        handler.handle(RemoveGithubLinkCommand(link.id))

        verify { vaultWriter.deleteSecret("secret/data/agents/projects/${link.projectId}/repos/${link.id}") }
        verify { links.delete(link.id) }
    }

    @Test
    fun `handle is a no-op for unknown link id`() {
        val id = GithubLinkId.random()
        every { links.findById(id) } returns null
        handler.handle(RemoveGithubLinkCommand(id))
        verify(exactly = 0) { vaultWriter.deleteSecret(any()) }
        verify(exactly = 0) { links.delete(any()) }
    }

    @Test
    fun `handle survives a vault remove failure and still deletes the row`() {
        val link =
            GithubLink(
                id = GithubLinkId.random(),
                projectId = ProjectId.random(),
                name = "x",
                repoUrl = "git@github.com:o/r.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { links.findById(link.id) } returns link
        every { vaultWriter.deleteSecret(any()) } throws RuntimeException("vault unreachable")

        handler.handle(RemoveGithubLinkCommand(link.id))

        verify { links.delete(link.id) }
    }
}

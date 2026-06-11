package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant

class RemoveGithubLinkCommandHandlerTest {
    private val links = mockk<GithubLinkRepository>(relaxed = true)
    private val deployKeys = mockk<DeployKeyStore>(relaxed = true)
    private val deployKeysProvider =
        mockk<org.springframework.beans.factory.ObjectProvider<DeployKeyStore>> {
            every { ifAvailable } returns deployKeys
        }
    private val handler = RemoveGithubLinkCommandHandler(links, deployKeysProvider)

    @Test
    fun `handle removes vault key then deletes the link row`() {
        val link =
            GithubLink(
                id = GithubLinkId.random(),
                projectId = ProjectId.random(),
                name = "x",
                repoUrl = "git@github.com:o/r.git",
                defaultBranch = "main",
                vaultKeyPath = "secret/data/agents/projects/p/repos/l",
                deployKeyFingerprint = "SHA256:abc",
                deployKeyAddedAt = Instant.now(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { links.findById(link.id) } returns link

        handler.handle(RemoveGithubLinkCommand(link.id))

        verify { deployKeys.remove(link.projectId, link.id) }
        verify { links.delete(link.id) }
    }

    @Test
    fun `handle is a no-op for unknown link id`() {
        val id = GithubLinkId.random()
        every { links.findById(id) } returns null
        handler.handle(RemoveGithubLinkCommand(id))
        verify(exactly = 0) { deployKeys.remove(any(), any()) }
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
                vaultKeyPath = "secret/data/agents/projects/p/repos/l",
                deployKeyFingerprint = null,
                deployKeyAddedAt = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { links.findById(link.id) } returns link
        every { deployKeys.remove(any(), any()) } throws RuntimeException("vault unreachable")

        handler.handle(RemoveGithubLinkCommand(link.id))

        verify { links.delete(link.id) }
    }
}

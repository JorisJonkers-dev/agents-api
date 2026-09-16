package com.jorisjonkers.personalstack.agents.application.credential

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.GitHubAppInstallationTokenClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkspaceGitCredentialServiceTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val workspaceRepositories = mockk<WorkspaceRepositoryRepository>()
    private val repositories = mockk<RepositoryRepository>()
    private val tokens = mockk<GitHubAppInstallationTokenClient>()
    private val service = WorkspaceGitCredentialService(workspaces, workspaceRepositories, repositories, tokens)

    private val primary =
        Repository(
            id = RepositoryId.random(),
            name = "agents",
            repoUrl = "https://github.com/JorisJonkers-dev/agents.git",
            defaultBranch = "main",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun workspace(
        id: WorkspaceId,
        repositoryId: RepositoryId? = primary.id,
    ) = Workspace(
        id = id,
        name = "demo",
        repoUrl = primary.repoUrl,
        branch = "main",
        podName = null,
        pvcName = null,
        gatewayEndpoint = null,
        status = WorkspaceStatus.READY,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        repositoryId = repositoryId,
    )

    @Test
    fun `denies an unknown workspace`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns null

        val result = service.mintFor(id, primary.repoUrl)

        assertThat(result).isEqualTo(WorkspaceGitCredentialService.Result.Denied("unknown workspace"))
    }

    @Test
    fun `denies when GitHub App token minting is disabled`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns workspace(id)
        every { tokens.enabled } returns false

        val result = service.mintFor(id, primary.repoUrl)

        assertThat(
            result,
        ).isEqualTo(WorkspaceGitCredentialService.Result.Denied("github app token minting is disabled"))
    }

    @Test
    fun `denies a repo the workspace does not own, and never calls mint`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns workspace(id)
        every { workspaceRepositories.findAllByWorkspaceId(id) } returns emptyList()
        every { repositories.findById(primary.id) } returns primary
        every { tokens.enabled } returns true

        val result = service.mintFor(id, "https://github.com/some-other-org/private-repo.git")

        assertThat(result).isEqualTo(
            WorkspaceGitCredentialService.Result.Denied("repository is not attached to this workspace"),
        )
        verify(exactly = 0) { tokens.mint(any(), any()) }
    }

    @Test
    fun `mints a token scoped to the requested repo, passing its workspace siblings`() {
        val id = WorkspaceId.random()
        val sibling =
            primary.copy(
                id = RepositoryId.random(),
                name = "sibling",
                repoUrl = "https://github.com/JorisJonkers-dev/sibling.git",
            )
        every { workspaces.findById(id) } returns workspace(id)
        every { workspaceRepositories.findAllByWorkspaceId(id) } returns
            listOf(
                WorkspaceRepositoryRepository.Link(id, sibling.id, isPrimary = false, attachedAt = Instant.now()),
            )
        every { repositories.findById(primary.id) } returns primary
        every { repositories.findById(sibling.id) } returns sibling
        every { tokens.enabled } returns true
        val expiresAt = Instant.parse("2026-01-01T00:00:00Z")
        every { tokens.mint(primary.repoUrl, setOf(sibling.repoUrl)) } returns
            GitHubAppInstallationTokenClient.InstallationToken("ghs_abc", expiresAt)

        val result = service.mintFor(id, primary.repoUrl)

        assertThat(result).isEqualTo(WorkspaceGitCredentialService.Result.Success("ghs_abc", expiresAt))
    }

    @Test
    fun `denies when the token mint itself fails`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns workspace(id)
        every { workspaceRepositories.findAllByWorkspaceId(id) } returns emptyList()
        every { repositories.findById(primary.id) } returns primary
        every { tokens.enabled } returns true
        every { tokens.mint(primary.repoUrl, emptySet()) } returns null

        val result = service.mintFor(id, primary.repoUrl)

        assertThat(result).isEqualTo(WorkspaceGitCredentialService.Result.Denied("token mint failed"))
    }
}

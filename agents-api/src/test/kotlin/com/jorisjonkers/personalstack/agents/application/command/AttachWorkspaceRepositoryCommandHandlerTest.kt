package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class AttachWorkspaceRepositoryCommandHandlerTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val repositories = mockk<RepositoryRepository>()
    private val links = mockk<WorkspaceRepositoryRepository>(relaxed = true)
    private val gateway = mockk<AgentGatewayClient>(relaxed = true)
    private val handler = AttachWorkspaceRepositoryCommandHandler(workspaces, repositories, links, gateway)

    private val workspaceId = WorkspaceId.random()
    private val repositoryId = RepositoryId.random()
    private val command = AttachWorkspaceRepositoryCommand(workspaceId, repositoryId)

    @Test
    fun `attaches a non-primary link and clones into a running workspace`() {
        val workspace = workspace()
        val repository = repository()
        every { workspaces.findById(workspaceId) } returns workspace
        every { repositories.findById(repositoryId) } returns repository
        every { gateway.isReady(workspace) } returns true

        handler.handle(command)

        verify { links.attach(workspaceId, repositoryId, isPrimary = false) }
        verify { gateway.clone(workspace, repository.repoUrl, repository.defaultBranch) }
    }

    @Test
    fun `attaches without live clone when workspace has no gateway yet`() {
        val workspace = workspace(gatewayEndpoint = null)
        every { workspaces.findById(workspaceId) } returns workspace
        every { repositories.findById(repositoryId) } returns repository()

        handler.handle(command)

        verify { links.attach(workspaceId, repositoryId, isPrimary = false) }
        verify(exactly = 0) { gateway.clone(any(), any(), any()) }
    }

    @Test
    fun `attaches without live clone when gateway is not ready`() {
        val workspace = workspace()
        every { workspaces.findById(workspaceId) } returns workspace
        every { repositories.findById(repositoryId) } returns repository()
        every { gateway.isReady(workspace) } returns false

        handler.handle(command)

        verify { links.attach(workspaceId, repositoryId, isPrimary = false) }
        verify(exactly = 0) { gateway.clone(any(), any(), any()) }
    }

    @Test
    fun `rejects an unknown workspace`() {
        every { workspaces.findById(workspaceId) } returns null

        assertThrows<NoSuchElementException> { handler.handle(command) }
        verify(exactly = 0) { links.attach(any(), any(), any()) }
    }

    @Test
    fun `rejects an unknown repository`() {
        every { workspaces.findById(workspaceId) } returns workspace()
        every { repositories.findById(repositoryId) } returns null

        assertThrows<NoSuchElementException> { handler.handle(command) }
        verify(exactly = 0) { links.attach(any(), any(), any()) }
    }

    private fun workspace(gatewayEndpoint: String? = "http://runner:8090") =
        Workspace(
            id = workspaceId,
            name = "workspace",
            repoUrl = "git@github.com:o/primary.git",
            branch = "main",
            podName = "pod",
            pvcName = "pvc",
            gatewayEndpoint = gatewayEndpoint,
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun repository() =
        Repository(
            id = repositoryId,
            name = "website",
            repoUrl = "git@github.com:o/website.git",
            defaultBranch = "main",
            vaultKeyPath = "secret/data/agents/repositories/${repositoryId.value}",
            deployKeyFingerprint = null,
            deployKeyAddedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

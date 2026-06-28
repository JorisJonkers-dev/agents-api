package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class GetWorkspaceQueryServiceTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val workspaceRepositories = mockk<WorkspaceRepositoryRepository>()
    private val repositories = mockk<RepositoryRepository>()
    private val service = GetWorkspaceQueryService(workspaces, sessions, workspaceRepositories, repositories)

    @Test
    fun `getSummary returns workspace without enriching detail`() {
        val w = workspace()
        every { workspaces.findById(w.id) } returns w

        val result = service.getSummary(w.id)

        assertThat(result).isEqualTo(w)
        verify(exactly = 0) { sessions.findAllByWorkspaceId(any()) }
        verify(exactly = 0) { workspaceRepositories.findAllByWorkspaceId(any()) }
        verify(exactly = 0) { repositories.findById(any()) }
    }

    @Test
    fun `get returns workspace enriched with sessions and repositories`() {
        val w = workspace()
        val r1 = repository()
        val r2 = repository(name = "docs")
        val now = Instant.now()
        every { workspaces.findById(w.id) } returns w
        every { sessions.findAllByWorkspaceId(w.id) } returns emptyList()
        every { workspaceRepositories.findAllByWorkspaceId(w.id) } returns
            listOf(
                WorkspaceRepositoryRepository.Link(w.id, r1.id, isPrimary = true, now),
                WorkspaceRepositoryRepository.Link(w.id, r2.id, isPrimary = false, now),
            )
        every { repositories.findById(r1.id) } returns r1
        every { repositories.findById(r2.id) } returns r2

        val result = service.get(w.id) ?: error("expected workspace detail")

        assertThat(result.workspace).isEqualTo(w)
        assertThat(result.workspace.currentRunnerSetupId).isEqualTo(w.currentRunnerSetupId)
        assertThat(result.workspace.pendingRunnerSetupId).isEqualTo(w.pendingRunnerSetupId)
        assertThat(result.workspace.runnerSetupGeneration).isEqualTo(w.runnerSetupGeneration)
        assertThat(result.sessions).isEmpty()
        assertThat(result.repositories.map { it.repository }).containsExactly(r1, r2)
        assertThat(result.repositories.map { it.isPrimary }).containsExactly(true, false)
    }

    @Test
    fun `get includes denormalized primary repository when junction row is missing`() {
        val repoId = RepositoryId.random()
        val w = workspace(repositoryId = repoId)
        val r = repository(id = repoId)
        every { workspaces.findById(w.id) } returns w
        every { sessions.findAllByWorkspaceId(w.id) } returns emptyList()
        every { workspaceRepositories.findAllByWorkspaceId(w.id) } returns emptyList()
        every { repositories.findById(repoId) } returns r

        val result = service.get(w.id) ?: error("expected workspace detail")

        assertThat(result.repositories.map { it.repository }).containsExactly(r)
        assertThat(result.repositories.single().isPrimary).isTrue
        assertThat(result.repositories.single().attachedAt).isEqualTo(w.createdAt)
    }

    @Test
    fun `get returns null when workspace is absent`() {
        val workspaceId = WorkspaceId.random()
        every { workspaces.findById(workspaceId) } returns null

        val result = service.get(workspaceId)

        assertThat(result).isNull()
        verify(exactly = 0) { sessions.findAllByWorkspaceId(any()) }
        verify(exactly = 0) { workspaceRepositories.findAllByWorkspaceId(any()) }
        verify(exactly = 0) { repositories.findById(any()) }
    }

    @Test
    fun `get fails fast when a workspace repository link dangles`() {
        val w = workspace()
        val missingRepositoryId = RepositoryId.random()
        every { workspaces.findById(w.id) } returns w
        every { workspaceRepositories.findAllByWorkspaceId(w.id) } returns
            listOf(WorkspaceRepositoryRepository.Link(w.id, missingRepositoryId, isPrimary = false, Instant.now()))
        every { repositories.findById(missingRepositoryId) } returns null

        assertThatThrownBy { service.get(w.id) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("references missing repository ${missingRepositoryId.value}")
    }

    private fun workspace(
        id: WorkspaceId = WorkspaceId.random(),
        repositoryId: RepositoryId? = null,
    ) = Workspace(
        id = id,
        name = "demo",
        repoUrl = "git@github.com:owner/repo.git",
        branch = "main",
        podName = "pod",
        pvcName = "pvc",
        gatewayEndpoint = "http://endpoint:8090",
        status = WorkspaceStatus.READY,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        repositoryId = repositoryId,
        currentRunnerSetupId = AgentSetupId("default"),
        currentRunnerSetupVersion = AgentSetupVersion(1),
        pendingRunnerSetupId = AgentSetupId("gpu"),
        pendingRunnerSetupVersion = AgentSetupVersion(2),
        runnerSetupGeneration = 3,
    )

    private fun repository(
        id: RepositoryId = RepositoryId.random(),
        name: String = "agents",
    ) = Repository(
        id = id,
        name = name,
        repoUrl = "git@github.com:owner/$name.git",
        defaultBranch = "main",
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}

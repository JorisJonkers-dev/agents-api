package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

@Suppress("DEPRECATION")
class JooqWorkspaceRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var workspaces: WorkspaceRepository

    @Autowired
    private lateinit var repositories: RepositoryRepository

    @Autowired
    private lateinit var projects: ProjectsRepository

    private fun freshRepository(): Repository {
        val r =
            Repository(
                id = RepositoryId.random(),
                name = "wsr-${UUID.randomUUID()}",
                repoUrl = "u",
                defaultBranch = "main",
                vaultKeyPath = "x",
                deployKeyFingerprint = null,
                deployKeyAddedAt = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        repositories.save(r)
        return r
    }

    private fun freshProject(): Project {
        val p =
            Project(
                id = ProjectId.random(),
                name = "p",
                slug = "p-${UUID.randomUUID().toString().take(6)}",
                description = "",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        projects.save(p)
        return p
    }

    private fun newWorkspace(
        kind: WorkspaceKind = WorkspaceKind.REPO_BACKED,
        repositoryId: RepositoryId? = null,
        projectId: ProjectId? = null,
    ): Workspace {
        val now = Instant.now()
        return Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = "git@github.com:o/r.git",
            branch = "main",
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.PENDING,
            createdAt = now,
            updatedAt = now,
            kind = kind,
            repositoryId = repositoryId,
            projectId = projectId,
        )
    }

    @Test
    fun `save and findById round-trips kind, repositoryId, projectId`() {
        val repo = freshRepository()
        val project = freshProject()
        val w =
            newWorkspace(
                kind = WorkspaceKind.SCRATCH,
                repositoryId = repo.id,
                projectId = project.id,
            )
        workspaces.save(w)

        val loaded = workspaces.findById(w.id)
        assertThat(loaded).isNotNull
        assertThat(loaded!!.kind).isEqualTo(WorkspaceKind.SCRATCH)
        assertThat(loaded.repositoryId).isEqualTo(repo.id)
        assertThat(loaded.projectId).isEqualTo(project.id)
    }

    @Test
    fun `save updates pod info on conflict`() {
        val w = newWorkspace()
        workspaces.save(w)
        val withPod =
            w.copy(
                podName = "agent-runner-x",
                pvcName = "workspace-x",
                gatewayEndpoint = "http://x.svc:8090",
                status = WorkspaceStatus.STARTING,
                updatedAt = Instant.now(),
            )
        workspaces.save(withPod)

        val loaded = workspaces.findById(w.id)
        assertThat(loaded!!.podName).isEqualTo("agent-runner-x")
        assertThat(loaded.status).isEqualTo(WorkspaceStatus.STARTING)
    }

    @Test
    fun `findAllByStatusNot excludes the supplied status`() {
        val a = newWorkspace()
        val b = newWorkspace().copy(status = WorkspaceStatus.DESTROYED)
        workspaces.save(a)
        workspaces.save(b)

        val active = workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED)
        assertThat(active.map { it.id }).contains(a.id).doesNotContain(b.id)
    }

    @Test
    fun `delete removes the row`() {
        val w = newWorkspace()
        workspaces.save(w)
        workspaces.delete(w.id)
        assertThat(workspaces.findById(w.id)).isNull()
    }
}

package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
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
    fun `runner setup CAS stages promotes and fails by generation`() {
        val w = newWorkspace().copy(runnerSetupGeneration = 7)
        workspaces.save(w)

        val staged =
            workspaces.beginRunnerSetupOperation(
                id = w.id,
                expectedGeneration = 7,
                setupId = AgentSetupId.legacy(),
                setupVersion = AgentSetupVersion.initial(),
            )
        val staleStage =
            workspaces.beginRunnerSetupOperation(
                id = w.id,
                expectedGeneration = 7,
                setupId = AgentSetupId.legacy(),
                setupVersion = AgentSetupVersion.initial(),
            )

        assertThat(staged).isTrue()
        assertThat(staleStage).isFalse()

        val failed = workspaces.failRunnerSetupOperation(w.id, expectedGeneration = 8)
        val failedWorkspace = workspaces.findById(w.id)
        assertThat(failed).isTrue()
        assertThat(failedWorkspace!!.runnerSetupOperation).isEqualTo(RunnerSetupOperation.FAILED)

        val completed = workspaces.completeRunnerSetupOperation(w.id, expectedGeneration = 8)
        val loaded = workspaces.findById(w.id)

        assertThat(completed).isTrue()
        assertThat(loaded!!.currentRunnerSetupId).isEqualTo(AgentSetupId.legacy())
        assertThat(loaded.pendingRunnerSetupId).isNull()
        assertThat(loaded.runnerSetupOperation).isEqualTo(RunnerSetupOperation.IDLE)
    }

    @Test
    fun `releaseStaleRunnerSetupOperation resets a stale RESTARTING lease to IDLE and drops pending`() {
        val staleAt = Instant.parse("2026-05-19T11:00:00Z")
        val now = Instant.parse("2026-05-19T12:00:00Z")
        val olderThan = Instant.parse("2026-05-19T11:55:00Z")
        val w =
            newWorkspace().copy(
                runnerSetupGeneration = 4,
                pendingRunnerSetupId = AgentSetupId.legacy(),
                pendingRunnerSetupVersion = AgentSetupVersion.initial(),
                runnerSetupOperation = RunnerSetupOperation.RESTARTING,
                runnerSetupOperationStartedAt = staleAt,
                runnerSetupOperationUpdatedAt = staleAt,
            )
        workspaces.save(w)

        val released = workspaces.releaseStaleRunnerSetupOperation(w.id, olderThan, now)
        val loaded = workspaces.findById(w.id)

        assertThat(released).isTrue()
        assertThat(loaded!!.runnerSetupOperation).isEqualTo(RunnerSetupOperation.IDLE)
        assertThat(loaded.pendingRunnerSetupId).isNull()
        assertThat(loaded.pendingRunnerSetupVersion).isNull()
        assertThat(loaded.currentRunnerSetupId).isEqualTo(w.currentRunnerSetupId)
    }

    @Test
    fun `releaseStaleRunnerSetupOperation resets a stale FAILED lease to IDLE`() {
        val staleAt = Instant.parse("2026-05-19T10:00:00Z")
        val now = Instant.parse("2026-05-19T12:00:00Z")
        val olderThan = Instant.parse("2026-05-19T11:55:00Z")
        val w =
            newWorkspace().copy(
                runnerSetupOperation = RunnerSetupOperation.FAILED,
                runnerSetupOperationStartedAt = staleAt,
                runnerSetupOperationUpdatedAt = staleAt,
            )
        workspaces.save(w)

        val released = workspaces.releaseStaleRunnerSetupOperation(w.id, olderThan, now)

        assertThat(released).isTrue()
        assertThat(workspaces.findById(w.id)!!.runnerSetupOperation).isEqualTo(RunnerSetupOperation.IDLE)
    }

    @Test
    fun `releaseStaleRunnerSetupOperation no-ops on a lease newer than the threshold`() {
        val freshAt = Instant.parse("2026-05-19T11:59:00Z")
        val now = Instant.parse("2026-05-19T12:00:00Z")
        val olderThan = Instant.parse("2026-05-19T11:55:00Z")
        val w =
            newWorkspace().copy(
                pendingRunnerSetupId = AgentSetupId.legacy(),
                pendingRunnerSetupVersion = AgentSetupVersion.initial(),
                runnerSetupOperation = RunnerSetupOperation.RESTARTING,
                runnerSetupOperationStartedAt = freshAt,
                runnerSetupOperationUpdatedAt = freshAt,
            )
        workspaces.save(w)

        val released = workspaces.releaseStaleRunnerSetupOperation(w.id, olderThan, now)

        assertThat(released).isFalse()
        assertThat(workspaces.findById(w.id)!!.runnerSetupOperation).isEqualTo(RunnerSetupOperation.RESTARTING)
    }

    @Test
    fun `releaseStaleRunnerSetupOperation no-ops on an IDLE workspace`() {
        val now = Instant.parse("2026-05-19T12:00:00Z")
        val olderThan = Instant.parse("2026-05-19T11:55:00Z")
        val w = newWorkspace()
        workspaces.save(w)

        val released = workspaces.releaseStaleRunnerSetupOperation(w.id, olderThan, now)

        assertThat(released).isFalse()
        assertThat(workspaces.findById(w.id)!!.runnerSetupOperation).isEqualTo(RunnerSetupOperation.IDLE)
    }

    @Test
    fun `acquireBootLease sets lease id when no lease held`() {
        val w = newWorkspace()
        workspaces.save(w)

        val leaseId = java.util.UUID.randomUUID()
        val acquired = workspaces.acquireBootLease(w.id, leaseId)
        val loaded = workspaces.findById(w.id)

        assertThat(acquired).isTrue()
        assertThat(loaded!!.runnerBootLeaseId).isEqualTo(leaseId)
        assertThat(loaded.runnerBootStartedAt).isNotNull()
    }

    @Test
    fun `acquireBootLease is rejected when a lease is already held`() {
        val w = newWorkspace()
        workspaces.save(w)

        val first = java.util.UUID.randomUUID()
        val second = java.util.UUID.randomUUID()
        workspaces.acquireBootLease(w.id, first)

        val secondAcquired = workspaces.acquireBootLease(w.id, second)

        assertThat(secondAcquired).isFalse()
        assertThat(workspaces.findById(w.id)!!.runnerBootLeaseId).isEqualTo(first)
    }

    @Test
    fun `completeBootLease clears lease without incrementing attempt`() {
        val w = newWorkspace()
        workspaces.save(w)

        val leaseId = java.util.UUID.randomUUID()
        workspaces.acquireBootLease(w.id, leaseId)

        val completed = workspaces.completeBootLease(w.id, leaseId)
        val loaded = workspaces.findById(w.id)

        assertThat(completed).isTrue()
        assertThat(loaded!!.runnerBootLeaseId).isNull()
        assertThat(loaded.runnerBootAttempt).isEqualTo(0)
    }

    @Test
    fun `completeBootLease no-ops when lease id does not match`() {
        val w = newWorkspace()
        workspaces.save(w)

        val realLease = java.util.UUID.randomUUID()
        val wrongLease = java.util.UUID.randomUUID()
        workspaces.acquireBootLease(w.id, realLease)

        val completed = workspaces.completeBootLease(w.id, wrongLease)

        assertThat(completed).isFalse()
        assertThat(workspaces.findById(w.id)!!.runnerBootLeaseId).isEqualTo(realLease)
    }

    @Test
    fun `failBootLease clears lease and increments attempt`() {
        val w = newWorkspace()
        workspaces.save(w)

        val leaseId = java.util.UUID.randomUUID()
        workspaces.acquireBootLease(w.id, leaseId)

        val failed = workspaces.failBootLease(w.id, leaseId)
        val loaded = workspaces.findById(w.id)

        assertThat(failed).isTrue()
        assertThat(loaded!!.runnerBootLeaseId).isNull()
        assertThat(loaded.runnerBootAttempt).isEqualTo(1)
    }

    @Test
    fun `failBootLease no-ops when lease id does not match`() {
        val w = newWorkspace()
        workspaces.save(w)

        val realLease = java.util.UUID.randomUUID()
        val wrongLease = java.util.UUID.randomUUID()
        workspaces.acquireBootLease(w.id, realLease)

        val failed = workspaces.failBootLease(w.id, wrongLease)

        assertThat(failed).isFalse()
        assertThat(workspaces.findById(w.id)!!.runnerBootAttempt).isEqualTo(0)
    }

    @Test
    fun `releaseStaleBootLease releases a stale lease and increments attempt`() {
        val staleAt = Instant.parse("2026-05-01T10:00:00Z")
        val now = Instant.parse("2026-05-01T12:00:00Z")
        val olderThan = Instant.parse("2026-05-01T11:55:00Z")
        val leaseId = java.util.UUID.randomUUID()
        val w =
            newWorkspace().copy(
                runnerBootLeaseId = leaseId,
                runnerBootAttempt = 1,
                runnerBootStartedAt = staleAt,
                runnerBootUpdatedAt = staleAt,
            )
        workspaces.save(w)

        val released = workspaces.releaseStaleBootLease(w.id, olderThan, now)
        val loaded = workspaces.findById(w.id)

        assertThat(released).isTrue()
        assertThat(loaded!!.runnerBootLeaseId).isNull()
        assertThat(loaded.runnerBootAttempt).isEqualTo(2)
    }

    @Test
    fun `releaseStaleBootLease no-ops on a lease newer than the threshold`() {
        val freshAt = Instant.parse("2026-05-01T11:59:00Z")
        val now = Instant.parse("2026-05-01T12:00:00Z")
        val olderThan = Instant.parse("2026-05-01T11:55:00Z")
        val leaseId = java.util.UUID.randomUUID()
        val w =
            newWorkspace().copy(
                runnerBootLeaseId = leaseId,
                runnerBootUpdatedAt = freshAt,
            )
        workspaces.save(w)

        val released = workspaces.releaseStaleBootLease(w.id, olderThan, now)

        assertThat(released).isFalse()
        assertThat(workspaces.findById(w.id)!!.runnerBootLeaseId).isEqualTo(leaseId)
    }

    @Test
    fun `releaseStaleBootLease no-ops when no boot lease is held`() {
        val w = newWorkspace()
        workspaces.save(w)
        val now = Instant.parse("2026-05-01T12:00:00Z")
        val olderThan = Instant.parse("2026-05-01T11:55:00Z")

        val released = workspaces.releaseStaleBootLease(w.id, olderThan, now)

        assertThat(released).isFalse()
        assertThat(workspaces.findById(w.id)!!.runnerBootAttempt).isEqualTo(0)
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

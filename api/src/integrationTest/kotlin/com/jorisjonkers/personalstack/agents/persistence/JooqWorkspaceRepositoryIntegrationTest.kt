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

open class JooqWorkspaceRepositoryIntegrationSupport
    @Autowired
    constructor(
        protected val workspaces: WorkspaceRepository,
        private val repositories: RepositoryRepository,
        private val projects: ProjectsRepository,
    ) : IntegrationTestBase {
        protected fun freshRepository(): Repository {
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

        protected fun freshProject(): Project {
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

        protected fun newWorkspace(
            kind: WorkspaceKind = WorkspaceKind.REPO_BACKED,
            repositoryId: RepositoryId? = null,
            projectId: ProjectId? = null,
            ownerUserId: String? = null,
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
                ownerUserId = ownerUserId,
            )
        }
    }

class JooqWorkspaceRepositoryIntegrationTest
    @Autowired
    constructor(
        workspaces: WorkspaceRepository,
        repositories: RepositoryRepository,
        projects: ProjectsRepository,
    ) : JooqWorkspaceRepositoryIntegrationSupport(workspaces, repositories, projects) {
        @Test
        fun saveAndFindByIdRoundTripsKindOwnerUserIdRepositoryIdProjectId() {
            val repo = freshRepository()
            val project = freshProject()
            val w =
                newWorkspace(
                    kind = WorkspaceKind.SCRATCH,
                    repositoryId = repo.id,
                    projectId = project.id,
                    ownerUserId = "user-123",
                )
            workspaces.save(w)

            val loaded = (workspaces.findById(w.id)).required()
            assertThat(loaded.kind).isEqualTo(WorkspaceKind.SCRATCH)
            assertThat(loaded.ownerUserId).isEqualTo("user-123")
            assertThat(loaded.repositoryId).isEqualTo(repo.id)
            assertThat(loaded.projectId).isEqualTo(project.id)
        }

        @Test
        fun saveAndFindByIdAcceptsLegacyNullOwnerRows() {
            val w = newWorkspace(ownerUserId = null)
            workspaces.save(w)

            val loaded = (workspaces.findById(w.id)).required()

            assertThat(loaded.ownerUserId).isNull()
        }

        @Test
        fun saveUpdatesPodInfoOnConflict() {
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

            val loaded = workspaces.findById(w.id).required()
            assertThat(loaded.podName).isEqualTo("agent-runner-x")
            assertThat(loaded.status).isEqualTo(WorkspaceStatus.STARTING)
        }

        @Test
        fun savePreservesExistingOwnerOnUnrelatedConflictUpdate() {
            val w = newWorkspace(ownerUserId = "user-123")
            workspaces.save(w)
            val staleUpdate =
                w.copy(
                    ownerUserId = null,
                    podName = "agent-runner-x",
                    status = WorkspaceStatus.STARTING,
                    updatedAt = Instant.now(),
                )
            workspaces.save(staleUpdate)

            val loaded = workspaces.findById(w.id).required()

            assertThat(loaded.ownerUserId).isEqualTo("user-123")
            assertThat(loaded.podName).isEqualTo("agent-runner-x")
            assertThat(loaded.status).isEqualTo(WorkspaceStatus.STARTING)
        }
    }

class JooqWorkspaceRunnerSetupRepositoryIntegrationTest
    @Autowired
    constructor(
        workspaces: WorkspaceRepository,
        repositories: RepositoryRepository,
        projects: ProjectsRepository,
    ) : JooqWorkspaceRepositoryIntegrationSupport(workspaces, repositories, projects) {
        @Test
        fun runnerSetupCASStagesPromotesAndFailsByGeneration() {
            val w = newWorkspace().copy(runnerSetupGeneration = 7)
            workspaces.save(w)

            val staged =
                workspaces.beginRunnerSetupOperation(
                    WorkspaceRepository.RunnerSetupOperationRequest(
                        id = w.id,
                        expectedGeneration = 7,
                        setupId = AgentSetupId.legacy(),
                        setupVersion = AgentSetupVersion.initial(),
                    ),
                )
            val staleStage =
                workspaces.beginRunnerSetupOperation(
                    WorkspaceRepository.RunnerSetupOperationRequest(
                        id = w.id,
                        expectedGeneration = 7,
                        setupId = AgentSetupId.legacy(),
                        setupVersion = AgentSetupVersion.initial(),
                    ),
                )

            assertThat(staged).isTrue()
            assertThat(staleStage).isFalse()

            val failed = workspaces.failRunnerSetupOperation(w.id, expectedGeneration = 8)
            val failedWorkspace = workspaces.findById(w.id).required()
            assertThat(failed).isTrue()
            assertThat(failedWorkspace.required().runnerSetupOperation).isEqualTo(RunnerSetupOperation.FAILED)

            val completed = workspaces.completeRunnerSetupOperation(w.id, expectedGeneration = 8)
            val loaded = workspaces.findById(w.id).required()

            assertThat(completed).isTrue()
            assertThat(loaded.currentRunnerSetupId).isEqualTo(AgentSetupId.legacy())
            assertThat(loaded.pendingRunnerSetupId).isNull()
            assertThat(loaded.runnerSetupOperation).isEqualTo(RunnerSetupOperation.IDLE)
        }

        @Test
        fun releasestalerunnersetupoperationResetsAStaleRESTARTINGLeaseToIDLEAndDropsPending() {
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
            val loaded = workspaces.findById(w.id).required()

            assertThat(released).isTrue()
            assertThat(loaded.runnerSetupOperation).isEqualTo(RunnerSetupOperation.IDLE)
            assertThat(loaded.pendingRunnerSetupId).isNull()
            assertThat(loaded.pendingRunnerSetupVersion).isNull()
            assertThat(loaded.currentRunnerSetupId).isEqualTo(w.currentRunnerSetupId)
        }

        @Test
        fun releasestalerunnersetupoperationResetsAStaleFAILEDLeaseToIDLE() {
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
            assertThat(workspaces.findById(w.id).required().runnerSetupOperation).isEqualTo(RunnerSetupOperation.IDLE)
        }

        @Test
        fun releasestalerunnersetupoperationNoOpsOnALeaseNewerThanTheThreshold() {
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
            assertThat(
                workspaces.findById(w.id).required().runnerSetupOperation,
            ).isEqualTo(RunnerSetupOperation.RESTARTING)
        }

        @Test
        fun releasestalerunnersetupoperationNoOpsOnAnIDLEWorkspace() {
            val now = Instant.parse("2026-05-19T12:00:00Z")
            val olderThan = Instant.parse("2026-05-19T11:55:00Z")
            val w = newWorkspace()
            workspaces.save(w)

            val released = workspaces.releaseStaleRunnerSetupOperation(w.id, olderThan, now)

            assertThat(released).isFalse()
            assertThat(workspaces.findById(w.id).required().runnerSetupOperation).isEqualTo(RunnerSetupOperation.IDLE)
        }
    }

class JooqWorkspaceBootLeaseRepositoryIntegrationTest
    @Autowired
    constructor(
        workspaces: WorkspaceRepository,
        repositories: RepositoryRepository,
        projects: ProjectsRepository,
    ) : JooqWorkspaceRepositoryIntegrationSupport(workspaces, repositories, projects) {
        @Test
        fun acquirebootleaseSetsLeaseIdWhenNoLeaseHeld() {
            val w = newWorkspace()
            workspaces.save(w)

            val leaseId = java.util.UUID.randomUUID()
            val acquired = workspaces.acquireBootLease(w.id, leaseId)
            val loaded = workspaces.findById(w.id).required()

            assertThat(acquired).isTrue()
            assertThat(loaded.runnerBootLeaseId).isEqualTo(leaseId)
            assertThat(loaded.runnerBootStartedAt).isNotNull()
        }

        @Test
        fun acquirebootleaseIsRejectedWhenALeaseIsAlreadyHeld() {
            val w = newWorkspace()
            workspaces.save(w)

            val first = java.util.UUID.randomUUID()
            val second = java.util.UUID.randomUUID()
            workspaces.acquireBootLease(w.id, first)

            val secondAcquired = workspaces.acquireBootLease(w.id, second)

            assertThat(secondAcquired).isFalse()
            assertThat(workspaces.findById(w.id).required().runnerBootLeaseId).isEqualTo(first)
        }

        @Test
        fun completebootleaseClearsLeaseWithoutIncrementingAttempt() {
            val w = newWorkspace()
            workspaces.save(w)

            val leaseId = java.util.UUID.randomUUID()
            workspaces.acquireBootLease(w.id, leaseId)

            val completed = workspaces.completeBootLease(w.id, leaseId)
            val loaded = workspaces.findById(w.id).required()

            assertThat(completed).isTrue()
            assertThat(loaded.runnerBootLeaseId).isNull()
            assertThat(loaded.runnerBootAttempt).isEqualTo(0)
        }

        @Test
        fun completebootleaseNoOpsWhenLeaseIdDoesNotMatch() {
            val w = newWorkspace()
            workspaces.save(w)

            val realLease = java.util.UUID.randomUUID()
            val wrongLease = java.util.UUID.randomUUID()
            workspaces.acquireBootLease(w.id, realLease)

            val completed = workspaces.completeBootLease(w.id, wrongLease)

            assertThat(completed).isFalse()
            assertThat(workspaces.findById(w.id).required().runnerBootLeaseId).isEqualTo(realLease)
        }

        @Test
        fun failbootleaseClearsLeaseAndIncrementsAttempt() {
            val w = newWorkspace()
            workspaces.save(w)

            val leaseId = java.util.UUID.randomUUID()
            workspaces.acquireBootLease(w.id, leaseId)

            val failed = workspaces.failBootLease(w.id, leaseId)
            val loaded = workspaces.findById(w.id).required()

            assertThat(failed).isTrue()
            assertThat(loaded.runnerBootLeaseId).isNull()
            assertThat(loaded.runnerBootAttempt).isEqualTo(1)
        }

        @Test
        fun failbootleaseNoOpsWhenLeaseIdDoesNotMatch() {
            val w = newWorkspace()
            workspaces.save(w)

            val realLease = java.util.UUID.randomUUID()
            val wrongLease = java.util.UUID.randomUUID()
            workspaces.acquireBootLease(w.id, realLease)

            val failed = workspaces.failBootLease(w.id, wrongLease)

            assertThat(failed).isFalse()
            assertThat(workspaces.findById(w.id).required().runnerBootAttempt).isEqualTo(0)
        }

        @Test
        fun releasestalebootleaseReleasesAStaleLeaseAndIncrementsAttempt() {
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
            val loaded = workspaces.findById(w.id).required()

            assertThat(released).isTrue()
            assertThat(loaded.runnerBootLeaseId).isNull()
            assertThat(loaded.runnerBootAttempt).isEqualTo(2)
        }

        @Test
        fun releasestalebootleaseNoOpsOnALeaseNewerThanTheThreshold() {
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
            assertThat(workspaces.findById(w.id).required().runnerBootLeaseId).isEqualTo(leaseId)
        }

        @Test
        fun releasestalebootleaseNoOpsWhenNoBootLeaseIsHeld() {
            val w = newWorkspace()
            workspaces.save(w)
            val now = Instant.parse("2026-05-01T12:00:00Z")
            val olderThan = Instant.parse("2026-05-01T11:55:00Z")

            val released = workspaces.releaseStaleBootLease(w.id, olderThan, now)

            assertThat(released).isFalse()
            assertThat(workspaces.findById(w.id).required().runnerBootAttempt).isEqualTo(0)
        }
    }

class JooqWorkspaceListingRepositoryIntegrationTest
    @Autowired
    constructor(
        workspaces: WorkspaceRepository,
        repositories: RepositoryRepository,
        projects: ProjectsRepository,
    ) : JooqWorkspaceRepositoryIntegrationSupport(workspaces, repositories, projects) {
        @Test
        fun findallbystatusnotExcludesTheSuppliedStatus() {
            val a = newWorkspace()
            val b = newWorkspace().copy(status = WorkspaceStatus.DESTROYED)
            workspaces.save(a)
            workspaces.save(b)

            val active = workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED)
            assertThat(active.map { it.id }).contains(a.id).doesNotContain(b.id)
        }

        @Test
        fun deleteRemovesTheRow() {
            val w = newWorkspace()
            workspaces.save(w)
            workspaces.delete(w.id)
            assertThat(workspaces.findById(w.id)).isNull()
        }
    }

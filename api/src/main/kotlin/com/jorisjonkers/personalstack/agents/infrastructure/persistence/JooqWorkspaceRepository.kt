package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Uses jOOQ's untyped DSL rather than the codegen classes. Codegen
 * would work but the generated classes only appear after a successful
 * build with the new Flyway migrations applied; the untyped form
 * makes the boot path resilient to a fresh checkout that hasn't run
 * the codegen task yet. Same pattern as auth-api's
 * audit-log persistence, which predates the codegen plugin.
 */
@Repository
@Suppress("DEPRECATION")
class JooqWorkspaceRepository(
    private val dsl: DSLContext,
) : WorkspaceRepository {
    @Suppress("LongMethod")
    override fun save(workspace: Workspace): Workspace {
        val createdAt = workspace.createdAt.atOffset(ZoneOffset.UTC)
        val updatedAt = workspace.updatedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(WORKSPACES)
            .set(ID, workspace.id.value)
            .set(NAME, workspace.name)
            .set(REPO_URL, workspace.repoUrl)
            .set(BRANCH, workspace.branch)
            .set(POD_NAME, workspace.podName)
            .set(PVC_NAME, workspace.pvcName)
            .set(GATEWAY_ENDPOINT, workspace.gatewayEndpoint)
            .set(STATUS, workspace.status.name)
            .set(OWNER_USER_ID, workspace.ownerUserId)
            .set(GITHUB_LINK_ID, workspace.githubLinkId?.value)
            .set(REPOSITORY_ID, workspace.repositoryId?.value)
            .set(PROJECT_ID, workspace.projectId?.value)
            .set(KIND, workspace.kind.name)
            .set(CURRENT_RUNNER_SETUP_ID, workspace.currentRunnerSetupId.value)
            .set(CURRENT_RUNNER_SETUP_VERSION, workspace.currentRunnerSetupVersion.value)
            .set(PENDING_RUNNER_SETUP_ID, workspace.pendingRunnerSetupId?.value)
            .set(PENDING_RUNNER_SETUP_VERSION, workspace.pendingRunnerSetupVersion?.value)
            .set(RUNNER_SETUP_GENERATION, workspace.runnerSetupGeneration)
            .set(RUNNER_SETUP_OPERATION, workspace.runnerSetupOperation.name)
            .set(
                RUNNER_SETUP_OPERATION_STARTED_AT,
                workspace.runnerSetupOperationStartedAt?.atOffset(ZoneOffset.UTC),
            ).set(
                RUNNER_SETUP_OPERATION_UPDATED_AT,
                workspace.runnerSetupOperationUpdatedAt?.atOffset(ZoneOffset.UTC),
            ).set(RUNNER_BOOT_LEASE_ID, workspace.runnerBootLeaseId)
            .set(RUNNER_BOOT_ATTEMPT, workspace.runnerBootAttempt)
            .set(RUNNER_BOOT_STARTED_AT, workspace.runnerBootStartedAt?.atOffset(ZoneOffset.UTC))
            .set(RUNNER_BOOT_UPDATED_AT, workspace.runnerBootUpdatedAt?.atOffset(ZoneOffset.UTC))
            .set(CREATED_AT, createdAt)
            .set(UPDATED_AT, updatedAt)
            .onConflict(ID)
            .doUpdate()
            .set(NAME, workspace.name)
            .set(REPO_URL, workspace.repoUrl)
            .set(BRANCH, workspace.branch)
            .set(POD_NAME, workspace.podName)
            .set(PVC_NAME, workspace.pvcName)
            .set(GATEWAY_ENDPOINT, workspace.gatewayEndpoint)
            .set(STATUS, workspace.status.name)
            .set(
                OWNER_USER_ID,
                DSL.coalesce(
                    DSL.excluded(OWNER_USER_ID),
                    DSL.field(DSL.name("workspaces", "owner_user_id"), String::class.java),
                ),
            ).set(GITHUB_LINK_ID, workspace.githubLinkId?.value)
            .set(REPOSITORY_ID, workspace.repositoryId?.value)
            .set(PROJECT_ID, workspace.projectId?.value)
            .set(KIND, workspace.kind.name)
            .set(UPDATED_AT, updatedAt)
            .execute()
        return workspace
    }

    override fun findById(id: WorkspaceId): Workspace? =
        dsl
            .selectFrom(WORKSPACES)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toWorkspace()

    override fun findAllByStatusNot(status: WorkspaceStatus): List<Workspace> =
        dsl
            .selectFrom(WORKSPACES)
            .where(STATUS.ne(status.name))
            .orderBy(CREATED_AT.desc())
            .fetch()
            .map { it.toWorkspace() }

    override fun beginRunnerSetupOperation(request: WorkspaceRepository.RunnerSetupOperationRequest): Boolean =
        dsl
            .update(WORKSPACES)
            .set(PENDING_RUNNER_SETUP_ID, request.setupId.value)
            .set(PENDING_RUNNER_SETUP_VERSION, request.setupVersion.value)
            .set(RUNNER_SETUP_GENERATION, request.expectedGeneration + 1)
            .set(RUNNER_SETUP_OPERATION, request.operation.name)
            .set(RUNNER_SETUP_OPERATION_STARTED_AT, request.now.atOffset(ZoneOffset.UTC))
            .set(RUNNER_SETUP_OPERATION_UPDATED_AT, request.now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, request.now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(request.id.value))
            .and(RUNNER_SETUP_GENERATION.eq(request.expectedGeneration))
            .and(RUNNER_SETUP_OPERATION.eq(RunnerSetupOperation.IDLE.name))
            .execute() == 1

    override fun completeRunnerSetupOperation(
        id: WorkspaceId,
        expectedGeneration: Long,
        now: Instant,
    ): Boolean =
        dsl
            .update(WORKSPACES)
            .set(CURRENT_RUNNER_SETUP_ID, PENDING_RUNNER_SETUP_ID)
            .set(CURRENT_RUNNER_SETUP_VERSION, PENDING_RUNNER_SETUP_VERSION)
            .set(PENDING_RUNNER_SETUP_ID, null as String?)
            .set(PENDING_RUNNER_SETUP_VERSION, null as Long?)
            .set(RUNNER_SETUP_OPERATION, RunnerSetupOperation.IDLE.name)
            .set(RUNNER_SETUP_OPERATION_UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(RUNNER_SETUP_GENERATION.eq(expectedGeneration))
            .and(PENDING_RUNNER_SETUP_ID.isNotNull)
            .and(PENDING_RUNNER_SETUP_VERSION.isNotNull)
            .execute() == 1

    override fun failRunnerSetupOperation(
        id: WorkspaceId,
        expectedGeneration: Long,
        now: Instant,
    ): Boolean =
        dsl
            .update(WORKSPACES)
            .set(RUNNER_SETUP_OPERATION, RunnerSetupOperation.FAILED.name)
            .set(RUNNER_SETUP_OPERATION_UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(RUNNER_SETUP_GENERATION.eq(expectedGeneration))
            .execute() == 1

    override fun releaseStaleRunnerSetupOperation(
        id: WorkspaceId,
        olderThan: Instant,
        now: Instant,
    ): Boolean =
        dsl
            .update(WORKSPACES)
            .set(RUNNER_SETUP_OPERATION, RunnerSetupOperation.IDLE.name)
            .set(PENDING_RUNNER_SETUP_ID, null as String?)
            .set(PENDING_RUNNER_SETUP_VERSION, null as Long?)
            .set(RUNNER_SETUP_OPERATION_UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(
                RUNNER_SETUP_OPERATION.`in`(
                    RunnerSetupOperation.RESTARTING.name,
                    RunnerSetupOperation.FAILED.name,
                ),
            ).and(RUNNER_SETUP_OPERATION_UPDATED_AT.lessThan(olderThan.atOffset(ZoneOffset.UTC)))
            .execute() == 1

    override fun acquireBootLease(
        id: WorkspaceId,
        leaseId: UUID,
        now: Instant,
    ): Boolean =
        dsl
            .update(WORKSPACES)
            .set(RUNNER_BOOT_LEASE_ID, leaseId)
            .set(RUNNER_BOOT_STARTED_AT, now.atOffset(ZoneOffset.UTC))
            .set(RUNNER_BOOT_UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(RUNNER_BOOT_LEASE_ID.isNull)
            .execute() == 1

    override fun completeBootLease(
        id: WorkspaceId,
        leaseId: UUID,
        now: Instant,
    ): Boolean =
        dsl
            .update(WORKSPACES)
            .set(RUNNER_BOOT_LEASE_ID, null as UUID?)
            .set(RUNNER_BOOT_STARTED_AT, null as OffsetDateTime?)
            .set(RUNNER_BOOT_UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(RUNNER_BOOT_LEASE_ID.eq(leaseId))
            .execute() == 1

    override fun failBootLease(
        id: WorkspaceId,
        leaseId: UUID,
        now: Instant,
    ): Boolean =
        dsl
            .update(WORKSPACES)
            .set(RUNNER_BOOT_LEASE_ID, null as UUID?)
            .set(RUNNER_BOOT_ATTEMPT, RUNNER_BOOT_ATTEMPT.plus(1))
            .set(RUNNER_BOOT_STARTED_AT, null as OffsetDateTime?)
            .set(RUNNER_BOOT_UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(RUNNER_BOOT_LEASE_ID.eq(leaseId))
            .execute() == 1

    override fun releaseStaleBootLease(
        id: WorkspaceId,
        olderThan: Instant,
        now: Instant,
    ): Boolean =
        dsl
            .update(WORKSPACES)
            .set(RUNNER_BOOT_LEASE_ID, null as UUID?)
            .set(RUNNER_BOOT_ATTEMPT, RUNNER_BOOT_ATTEMPT.plus(1))
            .set(RUNNER_BOOT_STARTED_AT, null as OffsetDateTime?)
            .set(RUNNER_BOOT_UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(RUNNER_BOOT_LEASE_ID.isNotNull)
            .and(RUNNER_BOOT_UPDATED_AT.lessThan(olderThan.atOffset(ZoneOffset.UTC)))
            .execute() == 1

    override fun delete(id: WorkspaceId) {
        dsl.deleteFrom(WORKSPACES).where(ID.eq(id.value)).execute()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun Record.toWorkspace(): Workspace =
        Workspace(
            id = WorkspaceId(this[ID]),
            name = this[NAME],
            repoUrl = this[REPO_URL],
            branch = this[BRANCH],
            podName = this[POD_NAME],
            pvcName = this[PVC_NAME],
            gatewayEndpoint = this[GATEWAY_ENDPOINT],
            status = WorkspaceStatus.valueOf(this[STATUS]),
            ownerUserId = this[OWNER_USER_ID],
            githubLinkId = this[GITHUB_LINK_ID]?.let { GithubLinkId(it) },
            repositoryId = this[REPOSITORY_ID]?.let { RepositoryId(it) },
            projectId = this[PROJECT_ID]?.let { ProjectId(it) },
            kind = WorkspaceKind.valueOf(this[KIND]),
            currentRunnerSetupId = AgentSetupId(this[CURRENT_RUNNER_SETUP_ID] ?: "default"),
            currentRunnerSetupVersion = AgentSetupVersion(this[CURRENT_RUNNER_SETUP_VERSION] ?: 1),
            pendingRunnerSetupId = this[PENDING_RUNNER_SETUP_ID]?.let { AgentSetupId(it) },
            pendingRunnerSetupVersion = this[PENDING_RUNNER_SETUP_VERSION]?.let { AgentSetupVersion(it) },
            runnerSetupGeneration = this[RUNNER_SETUP_GENERATION] ?: 0,
            runnerSetupOperation = RunnerSetupOperation.valueOf(this[RUNNER_SETUP_OPERATION] ?: "IDLE"),
            runnerSetupOperationStartedAt = this[RUNNER_SETUP_OPERATION_STARTED_AT]?.toInstant(),
            runnerSetupOperationUpdatedAt = this[RUNNER_SETUP_OPERATION_UPDATED_AT]?.toInstant(),
            runnerBootLeaseId = this[RUNNER_BOOT_LEASE_ID],
            runnerBootAttempt = this[RUNNER_BOOT_ATTEMPT] ?: 0,
            runnerBootStartedAt = this[RUNNER_BOOT_STARTED_AT]?.toInstant(),
            runnerBootUpdatedAt = this[RUNNER_BOOT_UPDATED_AT]?.toInstant(),
            createdAt = this[CREATED_AT].toInstant(),
            updatedAt = this[UPDATED_AT].toInstant(),
        )

    companion object {
        @JvmStatic val WORKSPACES = DSL.table("workspaces")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val NAME = DSL.field("name", String::class.java)

        @JvmStatic val REPO_URL = DSL.field("repo_url", String::class.java)

        @JvmStatic val BRANCH = DSL.field("branch", String::class.java)

        @JvmStatic val POD_NAME = DSL.field("pod_name", String::class.java)

        @JvmStatic val PVC_NAME = DSL.field("pvc_name", String::class.java)

        @JvmStatic val GATEWAY_ENDPOINT = DSL.field("gateway_endpoint", String::class.java)

        @JvmStatic val STATUS = DSL.field("status", String::class.java)

        @JvmStatic val OWNER_USER_ID = DSL.field("owner_user_id", String::class.java)

        @JvmStatic val GITHUB_LINK_ID = DSL.field("github_link_id", UUID::class.java)

        @JvmStatic val REPOSITORY_ID = DSL.field("repository_id", UUID::class.java)

        @JvmStatic val PROJECT_ID = DSL.field("project_id", UUID::class.java)

        @JvmStatic val KIND = DSL.field("kind", String::class.java)

        @JvmStatic val CURRENT_RUNNER_SETUP_ID = DSL.field("current_runner_setup_id", String::class.java)

        @JvmStatic val CURRENT_RUNNER_SETUP_VERSION =
            DSL.field("current_runner_setup_version", Long::class.javaObjectType)

        @JvmStatic val PENDING_RUNNER_SETUP_ID = DSL.field("pending_runner_setup_id", String::class.java)

        @JvmStatic val PENDING_RUNNER_SETUP_VERSION =
            DSL.field("pending_runner_setup_version", Long::class.javaObjectType)

        @JvmStatic val RUNNER_SETUP_GENERATION = DSL.field("runner_setup_generation", Long::class.javaObjectType)

        @JvmStatic val RUNNER_SETUP_OPERATION = DSL.field("runner_setup_operation", String::class.java)

        @JvmStatic val RUNNER_SETUP_OPERATION_STARTED_AT =
            DSL.field("runner_setup_operation_started_at", OffsetDateTime::class.java)

        @JvmStatic val RUNNER_SETUP_OPERATION_UPDATED_AT =
            DSL.field("runner_setup_operation_updated_at", OffsetDateTime::class.java)

        @JvmStatic val RUNNER_BOOT_LEASE_ID = DSL.field("runner_boot_lease_id", UUID::class.java)

        @JvmStatic val RUNNER_BOOT_ATTEMPT = DSL.field("runner_boot_attempt", Int::class.javaObjectType)

        @JvmStatic val RUNNER_BOOT_STARTED_AT =
            DSL.field("runner_boot_started_at", OffsetDateTime::class.java)

        @JvmStatic val RUNNER_BOOT_UPDATED_AT =
            DSL.field("runner_boot_updated_at", OffsetDateTime::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)

        @Suppress("unused")
        private val unusedLdt = LocalDateTime::class.java
    }
}

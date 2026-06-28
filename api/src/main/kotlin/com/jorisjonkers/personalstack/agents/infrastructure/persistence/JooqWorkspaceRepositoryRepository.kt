package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqWorkspaceRepositoryRepository(
    private val dsl: DSLContext,
) : WorkspaceRepositoryRepository {
    override fun attach(
        workspaceId: WorkspaceId,
        repositoryId: RepositoryId,
        isPrimary: Boolean,
    ): WorkspaceRepositoryRepository.Link {
        val now = Instant.now()
        if (isPrimary) {
            attachPrimary(workspaceId, repositoryId, now)
        } else {
            attachSecondary(workspaceId, repositoryId, now)
        }
        return findLink(workspaceId, repositoryId)
            ?: WorkspaceRepositoryRepository.Link(workspaceId, repositoryId, isPrimary, now)
    }

    private fun attachPrimary(
        workspaceId: WorkspaceId,
        repositoryId: RepositoryId,
        now: Instant,
    ) {
        dsl
            .update(JUNCTION)
            .set(IS_PRIMARY, false)
            .where(WORKSPACE_ID.eq(workspaceId.value))
            .execute()
        dsl
            .insertInto(JUNCTION)
            .set(WORKSPACE_ID, workspaceId.value)
            .set(REPOSITORY_ID, repositoryId.value)
            .set(IS_PRIMARY, true)
            .set(ATTACHED_AT, now.atOffset(ZoneOffset.UTC))
            .onConflict(WORKSPACE_ID, REPOSITORY_ID)
            .doUpdate()
            .set(IS_PRIMARY, true)
            .execute()
    }

    private fun attachSecondary(
        workspaceId: WorkspaceId,
        repositoryId: RepositoryId,
        now: Instant,
    ) {
        dsl
            .insertInto(JUNCTION)
            .set(WORKSPACE_ID, workspaceId.value)
            .set(REPOSITORY_ID, repositoryId.value)
            .set(IS_PRIMARY, false)
            .set(ATTACHED_AT, now.atOffset(ZoneOffset.UTC))
            .onConflict(WORKSPACE_ID, REPOSITORY_ID)
            .doNothing()
            .execute()
    }

    override fun detach(
        workspaceId: WorkspaceId,
        repositoryId: RepositoryId,
    ) {
        dsl
            .deleteFrom(JUNCTION)
            .where(WORKSPACE_ID.eq(workspaceId.value))
            .and(REPOSITORY_ID.eq(repositoryId.value))
            .execute()
    }

    override fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<WorkspaceRepositoryRepository.Link> =
        dsl
            .selectFrom(JUNCTION)
            .where(WORKSPACE_ID.eq(workspaceId.value))
            .orderBy(IS_PRIMARY.desc(), ATTACHED_AT.asc())
            .fetch()
            .map { it.toLink() }

    private fun findLink(
        workspaceId: WorkspaceId,
        repositoryId: RepositoryId,
    ): WorkspaceRepositoryRepository.Link? =
        dsl
            .selectFrom(JUNCTION)
            .where(WORKSPACE_ID.eq(workspaceId.value))
            .and(REPOSITORY_ID.eq(repositoryId.value))
            .fetchOne()
            ?.toLink()

    private fun Record.toLink(): WorkspaceRepositoryRepository.Link =
        WorkspaceRepositoryRepository.Link(
            workspaceId = WorkspaceId(this[WORKSPACE_ID]),
            repositoryId = RepositoryId(this[REPOSITORY_ID]),
            isPrimary = this[IS_PRIMARY],
            attachedAt = this[ATTACHED_AT].toInstant(),
        )

    companion object {
        @JvmStatic val JUNCTION = DSL.table("workspace_repositories")

        @JvmStatic val WORKSPACE_ID = DSL.field("workspace_id", UUID::class.java)

        @JvmStatic val REPOSITORY_ID = DSL.field("repository_id", UUID::class.java)

        @JvmStatic val IS_PRIMARY = DSL.field("is_primary", Boolean::class.java)

        @JvmStatic val ATTACHED_AT = DSL.field("attached_at", OffsetDateTime::class.java)
    }
}

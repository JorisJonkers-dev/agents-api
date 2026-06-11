package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqProjectRepositoryRepository(
    private val dsl: DSLContext,
) : ProjectRepositoryRepository {
    override fun link(
        projectId: ProjectId,
        repositoryId: RepositoryId,
    ): ProjectRepositoryRepository.Link {
        val now = Instant.now()
        val linkedAt = now.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(JUNCTION)
            .set(PROJECT_ID, projectId.value)
            .set(REPOSITORY_ID, repositoryId.value)
            .set(LINKED_AT, linkedAt)
            .onConflict(PROJECT_ID, REPOSITORY_ID)
            .doNothing()
            .execute()
        return ProjectRepositoryRepository.Link(projectId, repositoryId, now)
    }

    override fun unlink(
        projectId: ProjectId,
        repositoryId: RepositoryId,
    ) {
        dsl
            .deleteFrom(JUNCTION)
            .where(PROJECT_ID.eq(projectId.value))
            .and(REPOSITORY_ID.eq(repositoryId.value))
            .execute()
    }

    override fun exists(
        projectId: ProjectId,
        repositoryId: RepositoryId,
    ): Boolean =
        dsl
            .fetchExists(
                dsl
                    .selectOne()
                    .from(JUNCTION)
                    .where(PROJECT_ID.eq(projectId.value))
                    .and(REPOSITORY_ID.eq(repositoryId.value)),
            )

    override fun findAllByProjectId(projectId: ProjectId): List<ProjectRepositoryRepository.Link> =
        dsl
            .selectFrom(JUNCTION)
            .where(PROJECT_ID.eq(projectId.value))
            .orderBy(LINKED_AT.asc())
            .fetch()
            .map { it.toLink() }

    override fun findAllByRepositoryId(repositoryId: RepositoryId): List<ProjectRepositoryRepository.Link> =
        dsl
            .selectFrom(JUNCTION)
            .where(REPOSITORY_ID.eq(repositoryId.value))
            .orderBy(LINKED_AT.asc())
            .fetch()
            .map { it.toLink() }

    private fun Record.toLink(): ProjectRepositoryRepository.Link =
        ProjectRepositoryRepository.Link(
            projectId = ProjectId(this[PROJECT_ID]),
            repositoryId = RepositoryId(this[REPOSITORY_ID]),
            linkedAt = this[LINKED_AT].toInstant(),
        )

    companion object {
        @JvmStatic val JUNCTION = DSL.table("project_repositories")

        @JvmStatic val PROJECT_ID = DSL.field("project_id", UUID::class.java)

        @JvmStatic val REPOSITORY_ID = DSL.field("repository_id", UUID::class.java)

        @JvmStatic val LINKED_AT = DSL.field("linked_at", OffsetDateTime::class.java)
    }
}

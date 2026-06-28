package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqProjectsRepository(
    private val dsl: DSLContext,
) : ProjectsRepository {
    override fun save(project: Project): Project {
        val createdAt = project.createdAt.atOffset(ZoneOffset.UTC)
        val updatedAt = project.updatedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(PROJECTS)
            .set(ID, project.id.value)
            .set(NAME, project.name)
            .set(SLUG, project.slug)
            .set(DESCRIPTION, project.description)
            .set(CREATED_AT, createdAt)
            .set(UPDATED_AT, updatedAt)
            .onConflict(ID)
            .doUpdate()
            .set(NAME, project.name)
            .set(SLUG, project.slug)
            .set(DESCRIPTION, project.description)
            .set(UPDATED_AT, updatedAt)
            .execute()
        return project
    }

    override fun findById(id: ProjectId): Project? =
        dsl
            .selectFrom(PROJECTS)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toProject()

    override fun findBySlug(slug: String): Project? =
        dsl
            .selectFrom(PROJECTS)
            .where(SLUG.eq(slug))
            .fetchOne()
            ?.toProject()

    override fun findAll(): List<Project> =
        dsl
            .selectFrom(PROJECTS)
            .orderBy(CREATED_AT.desc())
            .fetch()
            .map { it.toProject() }

    override fun delete(id: ProjectId) {
        dsl.deleteFrom(PROJECTS).where(ID.eq(id.value)).execute()
    }

    private fun Record.toProject(): Project =
        Project(
            id = ProjectId(this[ID] as UUID),
            name = this[NAME] as String,
            slug = this[SLUG] as String,
            description = this[DESCRIPTION] as String,
            createdAt = (this[CREATED_AT] as OffsetDateTime).toInstant(),
            updatedAt = (this[UPDATED_AT] as OffsetDateTime).toInstant(),
        )

    companion object {
        @JvmStatic val PROJECTS = DSL.table("projects")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val NAME = DSL.field("name", String::class.java)

        @JvmStatic val SLUG = DSL.field("slug", String::class.java)

        @JvmStatic val DESCRIPTION = DSL.field("description", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)
    }
}

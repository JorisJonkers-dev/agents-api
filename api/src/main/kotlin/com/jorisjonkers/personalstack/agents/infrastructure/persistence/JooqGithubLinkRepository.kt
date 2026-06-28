package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqGithubLinkRepository(
    private val dsl: DSLContext,
) : GithubLinkRepository {
    override fun save(link: GithubLink): GithubLink {
        val createdAt = link.createdAt.atOffset(ZoneOffset.UTC)
        val updatedAt = link.updatedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(LINKS)
            .set(ID, link.id.value)
            .set(PROJECT_ID, link.projectId.value)
            .set(NAME, link.name)
            .set(REPO_URL, link.repoUrl)
            .set(DEFAULT_BRANCH, link.defaultBranch)
            .set(CREATED_AT, createdAt)
            .set(UPDATED_AT, updatedAt)
            .onConflict(ID)
            .doUpdate()
            .set(NAME, link.name)
            .set(REPO_URL, link.repoUrl)
            .set(DEFAULT_BRANCH, link.defaultBranch)
            .set(UPDATED_AT, updatedAt)
            .execute()
        return link
    }

    override fun findById(id: GithubLinkId): GithubLink? =
        dsl
            .selectFrom(LINKS)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toLink()

    override fun findAllByProjectId(projectId: ProjectId): List<GithubLink> =
        dsl
            .selectFrom(LINKS)
            .where(PROJECT_ID.eq(projectId.value))
            .orderBy(CREATED_AT.asc())
            .fetch()
            .map { it.toLink() }

    override fun delete(id: GithubLinkId) {
        dsl.deleteFrom(LINKS).where(ID.eq(id.value)).execute()
    }

    private fun Record.toLink(): GithubLink =
        GithubLink(
            id = GithubLinkId(this[ID]),
            projectId = ProjectId(this[PROJECT_ID]),
            name = this[NAME],
            repoUrl = this[REPO_URL],
            defaultBranch = this[DEFAULT_BRANCH],
            createdAt = this[CREATED_AT].toInstant(),
            updatedAt = this[UPDATED_AT].toInstant(),
        )

    companion object {
        @JvmStatic val LINKS = DSL.table("github_links")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val PROJECT_ID = DSL.field("project_id", UUID::class.java)

        @JvmStatic val NAME = DSL.field("name", String::class.java)

        @JvmStatic val REPO_URL = DSL.field("repo_url", String::class.java)

        @JvmStatic val DEFAULT_BRANCH = DSL.field("default_branch", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)
    }
}

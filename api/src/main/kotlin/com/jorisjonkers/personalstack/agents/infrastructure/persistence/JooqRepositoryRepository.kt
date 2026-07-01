package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.AccessVerification
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Repository as SpringRepository

@SpringRepository
class JooqRepositoryRepository(
    private val dsl: DSLContext,
) : RepositoryRepository {
    override fun save(repository: Repository): Repository {
        val createdAt = repository.createdAt.atOffset(ZoneOffset.UTC)
        val updatedAt = repository.updatedAt.atOffset(ZoneOffset.UTC)
        val verifiedAt = repository.verification?.checkedAt?.atOffset(ZoneOffset.UTC)
        val messages = repository.verification?.messages?.joinToString("\n")
        dsl
            .insertInto(REPOSITORIES)
            .set(ID, repository.id.value)
            .set(NAME, repository.name)
            .set(REPO_URL, repository.repoUrl)
            .set(DEFAULT_BRANCH, repository.defaultBranch)
            .set(VERIFY_PROTECTED, repository.verification?.defaultBranchProtected)
            .set(VERIFY_CHECKED_AT, verifiedAt)
            .set(VERIFY_MESSAGES, messages)
            .set(CREATED_AT, createdAt)
            .set(UPDATED_AT, updatedAt)
            .onConflict(ID)
            .doUpdate()
            .set(NAME, repository.name)
            .set(REPO_URL, repository.repoUrl)
            .set(DEFAULT_BRANCH, repository.defaultBranch)
            .set(VERIFY_PROTECTED, repository.verification?.defaultBranchProtected)
            .set(VERIFY_CHECKED_AT, verifiedAt)
            .set(VERIFY_MESSAGES, messages)
            .set(UPDATED_AT, updatedAt)
            .execute()
        return repository
    }

    override fun findById(id: RepositoryId): Repository? =
        dsl
            .selectFrom(REPOSITORIES)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toRepository()

    override fun findByName(name: String): Repository? =
        dsl
            .selectFrom(REPOSITORIES)
            .where(NAME.eq(name))
            // name is no longer unique (see V20): tolerate duplicates instead
            // of throwing, returning the first match.
            .fetchAny()
            ?.toRepository()

    override fun findByRepoUrl(repoUrl: String): Repository? =
        dsl
            .selectFrom(REPOSITORIES)
            .where(REPO_URL.eq(repoUrl))
            .fetchAny()
            ?.toRepository()

    override fun findAll(): List<Repository> =
        dsl
            .selectFrom(REPOSITORIES)
            .orderBy(CREATED_AT.desc())
            .fetch()
            .map { it.toRepository() }

    override fun findAllByProjectId(projectId: ProjectId): List<Repository> =
        dsl
            .select(
                ID,
                NAME,
                REPO_URL,
                DEFAULT_BRANCH,
                VERIFY_PROTECTED,
                VERIFY_CHECKED_AT,
                VERIFY_MESSAGES,
                CREATED_AT,
                UPDATED_AT,
            ).from(REPOSITORIES)
            .innerJoin(JUNCTION)
            .on(JUNCTION_REPOSITORY_ID.eq(ID))
            .where(JUNCTION_PROJECT_ID.eq(projectId.value))
            .orderBy(CREATED_AT.desc())
            .fetch()
            .map { it.toRepository() }

    override fun delete(id: RepositoryId) {
        dsl.deleteFrom(REPOSITORIES).where(ID.eq(id.value)).execute()
    }

    private fun Record.toRepository(): Repository =
        Repository(
            id = RepositoryId(this[ID]),
            name = this[NAME],
            repoUrl = this[REPO_URL],
            defaultBranch = this[DEFAULT_BRANCH],
            createdAt = this[CREATED_AT].toInstant(),
            updatedAt = this[UPDATED_AT].toInstant(),
            verification = toVerification(),
        )

    private fun Record.toVerification(): AccessVerification? {
        val checkedAt = this[VERIFY_CHECKED_AT]?.toInstant()
        val protected = this[VERIFY_PROTECTED]
        // A row is "verified" once the branch-protection check has run.
        // Pre-verify rows carry a null verification so the UI can
        // distinguish "never checked" from "checked, all null".
        val everChecked = listOf<Any?>(checkedAt, protected).any { it != null }
        if (!everChecked) return null
        val messages = this[VERIFY_MESSAGES]?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
        return AccessVerification(
            defaultBranchProtected = protected,
            checkedAt = checkedAt,
            messages = messages,
        )
    }

    companion object {
        @JvmStatic val REPOSITORIES = DSL.table("repositories")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val NAME = DSL.field("name", String::class.java)

        @JvmStatic val REPO_URL = DSL.field("repo_url", String::class.java)

        @JvmStatic val DEFAULT_BRANCH = DSL.field("default_branch", String::class.java)

        @JvmStatic val VERIFY_PROTECTED = DSL.field("verify_default_branch_protected", Boolean::class.javaObjectType)

        @JvmStatic val VERIFY_CHECKED_AT = DSL.field("verify_checked_at", OffsetDateTime::class.java)

        @JvmStatic val VERIFY_MESSAGES = DSL.field("verify_messages", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)

        @JvmStatic val JUNCTION = DSL.table("project_repositories")

        @JvmStatic val JUNCTION_PROJECT_ID = DSL.field("project_repositories.project_id", UUID::class.java)

        @JvmStatic val JUNCTION_REPOSITORY_ID = DSL.field("project_repositories.repository_id", UUID::class.java)
    }
}

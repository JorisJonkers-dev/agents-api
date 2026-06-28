package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class JooqProjectRepositoryRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var junction: ProjectRepositoryRepository

    @Autowired
    private lateinit var repositories: RepositoryRepository

    @Autowired
    private lateinit var projects: ProjectsRepository

    private fun project() =
        Project(
            id = ProjectId.random(),
            name = "p",
            slug = "p-${UUID.randomUUID().toString().take(6)}",
            description = "",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        ).also(projects::save)

    private fun repository() =
        Repository(
            id = RepositoryId.random(),
            name = "r-${UUID.randomUUID()}",
            repoUrl = "git@x:o/r.git",
            defaultBranch = "main",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        ).also(repositories::save)

    @Test
    fun `link inserts a row and exists returns true`() {
        val p = project()
        val r = repository()
        junction.link(p.id, r.id)
        assertThat(junction.exists(p.id, r.id)).isTrue
    }

    @Test
    fun `link is idempotent on duplicate insert`() {
        val p = project()
        val r = repository()
        junction.link(p.id, r.id)
        junction.link(p.id, r.id)
        assertThat(junction.findAllByProjectId(p.id)).hasSize(1)
    }

    @Test
    fun `unlink removes the row`() {
        val p = project()
        val r = repository()
        junction.link(p.id, r.id)
        junction.unlink(p.id, r.id)
        assertThat(junction.exists(p.id, r.id)).isFalse
    }

    @Test
    fun `findAllByProjectId returns junction rows for the project`() {
        val p = project()
        val r1 = repository()
        val r2 = repository()
        junction.link(p.id, r1.id)
        junction.link(p.id, r2.id)

        val links = junction.findAllByProjectId(p.id)
        assertThat(links.map { it.repositoryId }).containsExactlyInAnyOrder(r1.id, r2.id)
    }

    @Test
    fun `findAllByRepositoryId returns junction rows for the repository`() {
        val p1 = project()
        val p2 = project()
        val r = repository()
        junction.link(p1.id, r.id)
        junction.link(p2.id, r.id)

        val links = junction.findAllByRepositoryId(r.id)
        assertThat(links.map { it.projectId }).containsExactlyInAnyOrder(p1.id, p2.id)
    }
}

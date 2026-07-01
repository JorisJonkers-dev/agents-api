package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class JooqWorkspaceRepositoryRepositoryIntegrationTest
    @Autowired
    constructor(
        private val junction: WorkspaceRepositoryRepository,
        private val workspaces: WorkspaceRepository,
        private val repositories: RepositoryRepository,
    ) : IntegrationTestBase {
        private fun workspace() =
            Workspace(
                id = WorkspaceId.random(),
                name = "w",
                repoUrl = "git@github.com:o/primary.git",
                branch = "main",
                podName = null,
                pvcName = null,
                gatewayEndpoint = null,
                status = WorkspaceStatus.PENDING,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ).also(workspaces::save)

        private fun repository() =
            Repository(
                id = RepositoryId.random(),
                name = "r-${UUID.randomUUID()}",
                repoUrl = "git@github.com:o/r.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ).also(repositories::save)

        @Test
        fun attachInsertsARowCarriedByFindAllByWorkspaceId() {
            val w = workspace()
            val r = repository()
            junction.attach(w.id, r.id, isPrimary = true)

            val links = junction.findAllByWorkspaceId(w.id)
            assertThat(links).hasSize(1)
            assertThat(links.single().repositoryId).isEqualTo(r.id)
            assertThat(links.single().isPrimary).isTrue
        }

        @Test
        fun attachIsIdempotentOnDuplicateInsert() {
            val w = workspace()
            val r = repository()
            junction.attach(w.id, r.id)
            junction.attach(w.id, r.id)
            assertThat(junction.findAllByWorkspaceId(w.id)).hasSize(1)
        }

        @Test
        fun attachingARepositoryAsPrimaryPromotesItAndClearsPreviousPrimary() {
            val w = workspace()
            val r1 = repository()
            val r2 = repository()
            junction.attach(w.id, r1.id, isPrimary = true)
            junction.attach(w.id, r2.id)

            val promoted = junction.attach(w.id, r2.id, isPrimary = true)

            assertThat(promoted.isPrimary).isTrue
            val links = junction.findAllByWorkspaceId(w.id)
            assertThat(links.filter { it.isPrimary }.map { it.repositoryId }).containsExactly(r2.id)
            assertThat(links.first().repositoryId).isEqualTo(r2.id)
        }

        @Test
        fun detachRemovesTheRow() {
            val w = workspace()
            val r = repository()
            junction.attach(w.id, r.id)
            junction.detach(w.id, r.id)
            assertThat(junction.findAllByWorkspaceId(w.id)).isEmpty()
        }

        @Test
        fun findallbyworkspaceidReturnsEveryAttachedRepository() {
            val w = workspace()
            val r1 = repository()
            val r2 = repository()
            junction.attach(w.id, r1.id, isPrimary = true)
            junction.attach(w.id, r2.id)

            val links = junction.findAllByWorkspaceId(w.id)
            assertThat(links.map { it.repositoryId }).containsExactlyInAnyOrder(r1.id, r2.id)
            assertThat(links.filter { it.isPrimary }.map { it.repositoryId }).containsExactly(r1.id)
        }
    }

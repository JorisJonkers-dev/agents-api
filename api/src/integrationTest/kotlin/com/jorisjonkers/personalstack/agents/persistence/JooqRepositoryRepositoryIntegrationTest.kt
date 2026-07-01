package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.AccessVerification
import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class JooqRepositoryRepositoryIntegrationTest
    @Autowired
    constructor(
        private val repositories: RepositoryRepository,
        private val junction: ProjectRepositoryRepository,
        private val projects: ProjectsRepository,
    ) : IntegrationTestBase {
        private fun newRepository(name: String = "repo-${UUID.randomUUID()}") =
            Repository(
                id = RepositoryId.random(),
                name = name,
                repoUrl = "git@github.com:owner/$name.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )

        @Test
        fun saveAndFindByIdRoundTrip() {
            val r = newRepository()
            repositories.save(r)

            val loaded = (repositories.findById(r.id)).required()
            assertThat(loaded.name).isEqualTo(r.name)
            assertThat(loaded.repoUrl).isEqualTo(r.repoUrl)
        }

        @Test
        fun findbynameReturnsARepositoryByName() {
            val r = newRepository(name = "lookup-by-name-${UUID.randomUUID()}")
            repositories.save(r)

            val loaded = (repositories.findByName(r.name)).required()
            assertThat(loaded.id).isEqualTo(r.id)
        }

        @Test
        fun theSameNameCanBeSavedTwiceWithDifferentUrlsV20() {
            // Migration window: ".github" exists for both the old and new org.
            val name = "dup-name-${UUID.randomUUID()}"
            val old =
                Repository(
                    id = RepositoryId.random(),
                    name = name,
                    repoUrl = "git@github.com:JorisJonkers-dev/$name.git",
                    defaultBranch = "main",
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            val new =
                old.copy(
                    id = RepositoryId.random(),
                    repoUrl = "https://github.com/JorisJonkers-dev/$name",
                )
            repositories.save(old)
            repositories.save(new)

            assertThat(repositories.findById(old.id)).isNotNull
            assertThat(repositories.findById(new.id)).isNotNull
        }

        @Test
        fun findbyrepourlReturnsTheRepositoryByItsUrl() {
            val r = newRepository(name = "lookup-by-url-${UUID.randomUUID()}")
            repositories.save(r)

            val loaded = (repositories.findByRepoUrl(r.repoUrl)).required()
            assertThat(loaded.id).isEqualTo(r.id)
        }

        @Test
        fun saveUpdatesFingerprintOnConflict() {
            val r = newRepository()
            repositories.save(r)
            val withKey =
                r.copy(
                    updatedAt = Instant.now(),
                )
            repositories.save(withKey)

            val loaded = repositories.findById(r.id)
            assertThat(loaded.required().id).isEqualTo(r.id)
        }

        @Test
        fun findallbyprojectidReturnsOnlyLinkedRepositories() {
            val project =
                Project(
                    id = ProjectId.random(),
                    name = "test-project",
                    slug = "test-project-${UUID.randomUUID().toString().take(6)}",
                    description = "",
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            projects.save(project)
            val r1 = newRepository()
            val r2 = newRepository()
            val r3 = newRepository()
            repositories.save(r1)
            repositories.save(r2)
            repositories.save(r3)
            junction.link(project.id, r1.id)
            junction.link(project.id, r2.id)

            val attached = repositories.findAllByProjectId(project.id)

            assertThat(attached.map { it.id }).containsExactlyInAnyOrder(r1.id, r2.id)
        }

        @Test
        fun freshlySavedRepositoryHasANullVerificationUntilAProbeRuns() {
            val r = newRepository()
            repositories.save(r)
            assertThat(repositories.findById(r.id).required().verification).isNull()
        }

        @Test
        fun verificationRoundTripsThroughTheV11ColumnsIncludingMultiLineMessages() {
            val r = newRepository()
            repositories.save(r)
            val checkedAt = Instant.now()
            val withVerification =
                r.copy(
                    updatedAt = Instant.now(),
                    verification =
                        AccessVerification(
                            defaultBranchProtected = false,
                            checkedAt = checkedAt,
                            messages =
                                listOf(
                                    "default branch 'main' is NOT protected on GitHub",
                                    "second line for the multi-line round-trip",
                                ),
                        ),
                )
            repositories.save(withVerification)

            val loaded = (repositories.findById(r.id).required().verification).required()
            assertThat(loaded.defaultBranchProtected).isFalse
            assertThat(loaded.checkedAt).isCloseTo(checkedAt, within(1, ChronoUnit.SECONDS))
            assertThat(loaded.messages).hasSize(2)
            assertThat(loaded.messages).anyMatch { it.contains("NOT protected") }
            assertThat(loaded.messages).anyMatch { it.contains("multi-line") }
        }

        @Test
        fun nullBooleansWithACheckedAtStillLoadAsAnInconclusiveVerification() {
            val r = newRepository()
            repositories.save(r)
            repositories.save(
                r.copy(
                    updatedAt = Instant.now(),
                    verification =
                        AccessVerification(
                            defaultBranchProtected = null,
                            checkedAt = Instant.now(),
                            messages = listOf("deploy-key access could not be verified (verify gateway unavailable)"),
                        ),
                ),
            )

            val loaded = (repositories.findById(r.id).required().verification).required()
            assertThat(loaded.defaultBranchProtected).isNull()
            assertThat(loaded.messages).anyMatch { it.contains("could not be verified") }
        }

        @Test
        fun deleteRemovesTheRow() {
            val r = newRepository()
            repositories.save(r)
            repositories.delete(r.id)
            assertThat(repositories.findById(r.id)).isNull()
        }
    }

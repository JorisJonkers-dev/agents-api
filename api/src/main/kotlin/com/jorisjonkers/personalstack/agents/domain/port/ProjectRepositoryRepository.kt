package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import java.time.Instant

/**
 * Junction-table port for the project_repositories M:N mapping.
 * Operations are explicit (`link` / `unlink`) rather than save/find
 * because the row carries no payload beyond the pair + linkedAt.
 */
interface ProjectRepositoryRepository {
    data class Link(
        val projectId: ProjectId,
        val repositoryId: RepositoryId,
        val linkedAt: Instant,
    )

    fun link(
        projectId: ProjectId,
        repositoryId: RepositoryId,
    ): Link

    fun unlink(
        projectId: ProjectId,
        repositoryId: RepositoryId,
    )

    fun exists(
        projectId: ProjectId,
        repositoryId: RepositoryId,
    ): Boolean

    fun findAllByProjectId(projectId: ProjectId): List<Link>

    fun findAllByRepositoryId(repositoryId: RepositoryId): List<Link>
}

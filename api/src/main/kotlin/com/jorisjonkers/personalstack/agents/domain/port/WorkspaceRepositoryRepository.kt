package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import java.time.Instant

/**
 * Junction-table port for the workspace_repositories M:N mapping — the
 * repositories a workspace clones beyond its primary. Operations are explicit
 * (`attach` / `detach`) rather than save/find because the row carries no
 * payload beyond the pair, the primary flag, and attachedAt.
 */
interface WorkspaceRepositoryRepository {
    data class Link(
        val workspaceId: WorkspaceId,
        val repositoryId: RepositoryId,
        val isPrimary: Boolean,
        val attachedAt: Instant,
    )

    fun attach(
        workspaceId: WorkspaceId,
        repositoryId: RepositoryId,
        isPrimary: Boolean = false,
    ): Link

    fun detach(
        workspaceId: WorkspaceId,
        repositoryId: RepositoryId,
    )

    fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<Link>
}

package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class GetWorkspaceQueryService(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val workspaceRepositories: WorkspaceRepositoryRepository,
    private val repositories: RepositoryRepository,
) {
    data class WorkspaceView(
        val workspace: Workspace,
        val sessions: List<WorkspaceAgentSession>,
        val repositories: List<WorkspaceRepositoryView>,
    )

    data class WorkspaceRepositoryView(
        val repository: Repository,
        val isPrimary: Boolean,
        val attachedAt: Instant,
    )

    fun getSummary(id: WorkspaceId): Workspace? = workspaces.findById(id)

    fun get(id: WorkspaceId): WorkspaceView? {
        val workspace = workspaces.findById(id) ?: return null
        val links = repositoryLinks(workspace)
        val resolvedRepositories =
            links.map { link ->
                val repository =
                    repositories.findById(link.repositoryId)
                        ?: error(
                            "Workspace ${id.value} references missing repository ${link.repositoryId.value}",
                        )
                WorkspaceRepositoryView(repository, link.isPrimary, link.attachedAt)
            }
        return WorkspaceView(workspace, sessions.findAllByWorkspaceId(id), resolvedRepositories)
    }

    private fun repositoryLinks(workspace: Workspace): List<WorkspaceRepositoryRepository.Link> {
        val links = workspaceRepositories.findAllByWorkspaceId(workspace.id)
        val primaryRepositoryId = workspace.repositoryId ?: return links
        if (links.any { it.repositoryId == primaryRepositoryId }) return links
        val primaryLink =
            WorkspaceRepositoryRepository.Link(
                workspaceId = workspace.id,
                repositoryId = primaryRepositoryId,
                isPrimary = true,
                attachedAt = workspace.createdAt,
            )
        return listOf(primaryLink) + links
    }
}

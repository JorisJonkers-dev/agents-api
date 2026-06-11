package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import org.springframework.stereotype.Service

@Service
class RepositoryQueryService(
    private val repositories: RepositoryRepository,
    private val junction: ProjectRepositoryRepository,
    private val projects: ProjectsRepository,
) {
    data class RepositoryDetail(
        val repository: Repository,
        val attachedProjects: List<Project>,
    )

    fun list(): List<Repository> = repositories.findAll()

    fun listByProject(projectId: com.jorisjonkers.personalstack.agents.domain.model.ProjectId): List<Repository> =
        repositories.findAllByProjectId(projectId)

    fun get(id: RepositoryId): RepositoryDetail? {
        val repository = repositories.findById(id) ?: return null
        val attached =
            junction
                .findAllByRepositoryId(id)
                .mapNotNull { projects.findById(it.projectId) }
        return RepositoryDetail(repository, attached)
    }
}

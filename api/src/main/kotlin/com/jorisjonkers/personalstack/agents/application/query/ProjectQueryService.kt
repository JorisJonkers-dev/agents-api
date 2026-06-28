package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import org.springframework.stereotype.Service

@Service
class ProjectQueryService(
    private val projects: ProjectsRepository,
    private val links: GithubLinkRepository,
) {
    data class ProjectDetail(
        val project: Project,
        val links: List<GithubLink>,
    )

    fun list(): List<Project> = projects.findAll()

    fun get(id: ProjectId): ProjectDetail? {
        val project = projects.findById(id) ?: return null
        return ProjectDetail(project, links.findAllByProjectId(id))
    }
}

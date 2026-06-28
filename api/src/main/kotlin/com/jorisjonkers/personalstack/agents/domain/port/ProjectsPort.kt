package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId

interface ProjectsRepository {
    fun save(project: Project): Project

    fun findById(id: ProjectId): Project?

    fun findBySlug(slug: String): Project?

    fun findAll(): List<Project>

    fun delete(id: ProjectId)
}

interface GithubLinkRepository {
    fun save(link: GithubLink): GithubLink

    fun findById(id: GithubLinkId): GithubLink?

    fun findAllByProjectId(projectId: ProjectId): List<GithubLink>

    fun delete(id: GithubLinkId)
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class LinkRepositoryToProjectCommandHandler(
    private val projects: ProjectsRepository,
    private val repositories: RepositoryRepository,
    private val junction: ProjectRepositoryRepository,
) : CommandHandler<LinkRepositoryToProjectCommand> {
    @Transactional
    override fun handle(command: LinkRepositoryToProjectCommand) {
        projects.findById(command.projectId)
            ?: error("project not found: ${command.projectId}")
        repositories.findById(command.repositoryId)
            ?: error("repository not found: ${command.repositoryId}")
        junction.link(command.projectId, command.repositoryId)
    }
}

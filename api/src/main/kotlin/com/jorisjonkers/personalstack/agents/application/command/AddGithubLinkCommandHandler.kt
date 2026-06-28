package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Creates a (legacy) GithubLink row. Access is granted by installing
 * the GitHub App on the repository's owner — links carry no deploy
 * key, so the row is ready to use as soon as it is created.
 */
@Component
class AddGithubLinkCommandHandler(
    private val projects: ProjectsRepository,
    private val links: GithubLinkRepository,
) : CommandHandler<AddGithubLinkCommand> {
    @Transactional
    override fun handle(command: AddGithubLinkCommand) {
        val project =
            projects.findById(command.projectId)
                ?: error("project not found: ${command.projectId}")
        require(command.name.isNotBlank()) { "link name must not be blank" }
        require(command.repoUrl.isNotBlank()) { "repo URL must not be blank" }
        val now = Instant.now()
        links.save(
            GithubLink(
                id = command.linkId,
                projectId = project.id,
                name = command.name.trim(),
                repoUrl = command.repoUrl.trim(),
                defaultBranch = command.defaultBranch.ifBlank { "main" },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Creates the Repository row. Access is granted out-of-band by
 * installing the GitHub App on the repository's owner — no per-repo
 * credential is stored, so the row is ready to use immediately.
 */
@Component
class CreateRepositoryCommandHandler(
    private val repositories: RepositoryRepository,
) : CommandHandler<CreateRepositoryCommand> {
    @Transactional
    override fun handle(command: CreateRepositoryCommand) {
        require(command.name.isNotBlank()) { "repository name must not be blank" }
        require(command.repoUrl.isNotBlank()) { "repo URL must not be blank" }
        val existing = repositories.findByName(command.name.trim())
        if (existing != null && existing.id != command.repositoryId) {
            error("repository name already in use: ${command.name.trim()}")
        }
        val now = Instant.now()
        repositories.save(
            Repository(
                id = command.repositoryId,
                name = command.name.trim(),
                repoUrl = command.repoUrl.trim(),
                defaultBranch = command.defaultBranch.ifBlank { "main" },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

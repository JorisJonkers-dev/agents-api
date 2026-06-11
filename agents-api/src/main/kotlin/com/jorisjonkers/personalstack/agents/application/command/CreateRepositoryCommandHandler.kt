package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Creates the Repository row in "needs key" state. The Vault path
 * is pre-allocated under `secret/data/agents/repositories/<id>` so
 * the deploy-key wizard can deep-link to a stable URL even before
 * the operator pastes the key in. The fingerprint stays null until
 * [AttachRepositoryDeployKeyCommandHandler] runs.
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
        val vaultPath = "secret/data/agents/repositories/${command.repositoryId}"
        repositories.save(
            Repository(
                id = command.repositoryId,
                name = command.name.trim(),
                repoUrl = command.repoUrl.trim(),
                defaultBranch = command.defaultBranch.ifBlank { "main" },
                vaultKeyPath = vaultPath,
                deployKeyFingerprint = null,
                deployKeyAddedAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

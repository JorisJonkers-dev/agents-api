package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Deletes a repository row. The Vault key is best-effort removed —
 * a Vault outage doesn't block the row delete because the row is
 * the source of truth for "is this repo still active" and a stale
 * Vault entry is invisible without a row to point at it. The
 * junction-table rows cascade via the foreign-key ON DELETE CASCADE.
 */
@Component
class DeleteRepositoryCommandHandler(
    private val repositories: RepositoryRepository,
    private val deployKeysProvider: ObjectProvider<DeployKeyStore>,
) : CommandHandler<DeleteRepositoryCommand> {
    @Transactional
    override fun handle(command: DeleteRepositoryCommand) {
        val repository = repositories.findById(command.repositoryId) ?: return
        deployKeysProvider.ifAvailable?.let { ks ->
            runCatching { ks.remove(repository.id) }
        }
        repositories.delete(repository.id)
    }
}

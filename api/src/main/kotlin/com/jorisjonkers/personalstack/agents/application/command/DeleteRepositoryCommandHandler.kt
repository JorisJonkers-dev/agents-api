package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import com.jorisjonkers.personalstack.common.vault.VaultKeyValueWriter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Deletes a repository row. Any deploy-key secret left over from
 * before the GitHub App migration is best-effort removed from Vault —
 * a Vault outage (or no Vault at all) doesn't block the row delete,
 * since the row is the source of truth and an orphaned secret is
 * invisible without a row to point at it. The junction-table rows
 * cascade via the foreign-key ON DELETE CASCADE.
 */
@Component
class DeleteRepositoryCommandHandler(
    private val repositories: RepositoryRepository,
    private val vaultWriter: ObjectProvider<VaultKeyValueWriter>,
) : CommandHandler<DeleteRepositoryCommand> {
    @Transactional
    override fun handle(command: DeleteRepositoryCommand) {
        val repository = repositories.findById(command.repositoryId) ?: return
        vaultWriter.ifAvailable?.let { writer ->
            runCatching { writer.deleteSecret("secret/data/agents/repositories/${repository.id}") }
        }
        repositories.delete(repository.id)
    }
}

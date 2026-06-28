package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import com.jorisjonkers.personalstack.common.vault.VaultKeyValueWriter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class RemoveGithubLinkCommandHandler(
    private val links: GithubLinkRepository,
    private val vaultWriter: ObjectProvider<VaultKeyValueWriter>,
) : CommandHandler<RemoveGithubLinkCommand> {
    @Transactional
    override fun handle(command: RemoveGithubLinkCommand) {
        val link = links.findById(command.linkId) ?: return
        // Best-effort cleanup of any pre-migration deploy-key secret.
        vaultWriter.ifAvailable?.let { writer ->
            runCatching { writer.deleteSecret("secret/data/agents/projects/${link.projectId}/repos/${link.id}") }
        }
        links.delete(link.id)
    }
}

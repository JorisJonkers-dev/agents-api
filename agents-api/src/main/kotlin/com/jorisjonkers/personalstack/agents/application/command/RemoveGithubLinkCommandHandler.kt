package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class RemoveGithubLinkCommandHandler(
    private val links: GithubLinkRepository,
    private val deployKeysProvider: ObjectProvider<DeployKeyStore>,
) : CommandHandler<RemoveGithubLinkCommand> {
    @Transactional
    override fun handle(command: RemoveGithubLinkCommand) {
        val link = links.findById(command.linkId) ?: return
        deployKeysProvider.ifAvailable?.let { ks ->
            runCatching { ks.remove(link.projectId, link.id) }
        }
        links.delete(link.id)
    }
}

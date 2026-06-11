package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DetachWorkspaceRepositoryCommandHandler(
    private val links: WorkspaceRepositoryRepository,
) : CommandHandler<DetachWorkspaceRepositoryCommand> {
    @Transactional
    override fun handle(command: DetachWorkspaceRepositoryCommand) {
        val link =
            links
                .findAllByWorkspaceId(command.workspaceId)
                .firstOrNull { it.repositoryId == command.repositoryId }
        require(link == null || !link.isPrimary) {
            "cannot detach the primary repository from a workspace"
        }
        links.detach(command.workspaceId, command.repositoryId)
    }
}

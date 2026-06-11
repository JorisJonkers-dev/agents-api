package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AttachWorkspaceRepositoryCommandHandler(
    private val workspaces: WorkspaceRepository,
    private val repositories: RepositoryRepository,
    private val links: WorkspaceRepositoryRepository,
    private val gateway: AgentGatewayClient,
) : CommandHandler<AttachWorkspaceRepositoryCommand> {
    @Transactional
    override fun handle(command: AttachWorkspaceRepositoryCommand) {
        val workspace =
            workspaces.findById(command.workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${command.workspaceId}")
        val repository =
            repositories.findById(command.repositoryId)
                ?: throw NoSuchElementException("repository not found: ${command.repositoryId}")
        links.attach(command.workspaceId, command.repositoryId, isPrimary = false)
        if (workspace.gatewayEndpoint != null && gateway.isReady(workspace)) {
            gateway.clone(workspace, repository.repoUrl, repository.defaultBranch)
        }
    }
}

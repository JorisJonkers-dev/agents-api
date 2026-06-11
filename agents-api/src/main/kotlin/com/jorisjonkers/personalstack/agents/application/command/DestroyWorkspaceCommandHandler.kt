package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DestroyWorkspaceCommandHandler(
    private val workspaces: WorkspaceRepository,
    private val orchestrator: AgentRunnerOrchestrator,
) : CommandHandler<DestroyWorkspaceCommand> {
    @Transactional
    override fun handle(command: DestroyWorkspaceCommand) {
        val workspace = workspaces.findById(command.id) ?: return
        orchestrator.destroy(workspace)
        workspaces.save(workspace.markDestroyed())
    }
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component

/**
 * Thin pass-through to the gateway's /git/open-pr. The interesting
 * machinery (deploy key, gh CLI auth, repo dir conventions) lives in
 * the gateway; the API layer's job is just to address the right
 * workspace and let the result propagate as the command's stored
 * URL via a follow-up Turn ingest (Block protocol).
 */
@Component
class OpenPullRequestCommandHandler(
    private val workspaces: WorkspaceRepository,
    private val gateway: AgentGatewayClient,
) : CommandHandler<OpenPullRequestCommand> {
    override fun handle(command: OpenPullRequestCommand) {
        val workspace = workspaces.findById(command.workspaceId) ?: error("workspace not found: ${command.workspaceId}")
        require(!workspace.hasRunnerSetupGuard()) {
            "workspace runner setup operation is in progress: ${workspace.id.value}"
        }
        gateway.openPr(workspace, command.repoDir, command.title, command.body, command.base)
    }

    private fun Workspace.hasRunnerSetupGuard(): Boolean =
        runnerSetupOperation != RunnerSetupOperation.IDLE ||
            pendingRunnerSetupId != null ||
            pendingRunnerSetupVersion != null
}

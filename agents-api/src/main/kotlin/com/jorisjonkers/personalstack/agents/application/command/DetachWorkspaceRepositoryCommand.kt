package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.common.command.Command

/**
 * Detach an additional repository from a workspace. The primary repository
 * cannot be detached this way — it is owned by the workspace row itself.
 */
data class DetachWorkspaceRepositoryCommand(
    val workspaceId: WorkspaceId,
    val repositoryId: RepositoryId,
) : Command

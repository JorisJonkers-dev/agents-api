package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.common.command.Command

/**
 * Attach an additional repository to a workspace. The link becomes part
 * of REPO_URLS for future runner boots, and a currently running gateway
 * is asked to clone it immediately into /workspace/<repo-name>.
 */
data class AttachWorkspaceRepositoryCommand(
    val workspaceId: WorkspaceId,
    val repositoryId: RepositoryId,
) : Command

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.common.command.Command

data class DestroyWorkspaceCommand(
    val id: WorkspaceId,
) : Command

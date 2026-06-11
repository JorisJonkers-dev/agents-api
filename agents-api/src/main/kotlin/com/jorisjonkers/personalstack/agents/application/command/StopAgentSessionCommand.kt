package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.common.command.Command

data class StopAgentSessionCommand(
    val sessionId: WorkspaceAgentSessionId,
) : Command

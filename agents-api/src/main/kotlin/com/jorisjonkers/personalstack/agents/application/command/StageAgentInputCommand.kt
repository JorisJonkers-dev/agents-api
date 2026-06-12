package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId

data class StageAgentInputCommand(
    val workspaceId: WorkspaceId,
    val sessionId: WorkspaceAgentSessionId,
    val content: String,
    val name: String? = null,
)

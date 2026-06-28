package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.common.command.Command

data class StartHeadlessJobCommand(
    val sessionId: WorkspaceAgentSessionId,
    val workspaceId: WorkspaceId,
    val kind: WorkspaceAgentKind,
    val prompt: String,
    val timeoutSeconds: Long? = null,
    val setupId: AgentSetupId? = null,
    val setupVersion: AgentSetupVersion? = null,
) : Command

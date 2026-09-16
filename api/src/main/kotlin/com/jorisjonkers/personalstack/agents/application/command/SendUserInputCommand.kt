package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.common.command.Command

data class SendUserInputCommand(
    val sessionId: AgentSessionId,
    val text: String,
    val enter: Boolean = true,
) : Command

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.common.command.Command
import java.util.UUID

data class ArchiveChatSessionCommand(
    val sessionId: ChatSessionId,
    val userId: UUID,
) : Command

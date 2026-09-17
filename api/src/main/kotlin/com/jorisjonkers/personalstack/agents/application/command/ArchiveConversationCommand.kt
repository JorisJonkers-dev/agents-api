package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.common.command.Command
import java.util.UUID

data class ArchiveConversationCommand(
    val conversationId: ConversationId,
    val userId: UUID,
) : Command

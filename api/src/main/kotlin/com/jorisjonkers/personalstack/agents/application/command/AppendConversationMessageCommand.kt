package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageRole
import com.jorisjonkers.personalstack.common.command.Command

data class AppendConversationMessageCommand(
    val messageId: ConversationMessageId,
    val conversationId: ConversationId,
    val role: ConversationMessageRole,
    val body: String,
) : Command

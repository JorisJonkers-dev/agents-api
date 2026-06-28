package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.MessageId
import com.jorisjonkers.personalstack.agents.domain.model.MessageRole
import com.jorisjonkers.personalstack.common.command.Command

data class SendMessageCommand(
    val messageId: MessageId,
    val conversationId: ConversationId,
    val userId: String,
    val content: String,
    val role: MessageRole,
) : Command

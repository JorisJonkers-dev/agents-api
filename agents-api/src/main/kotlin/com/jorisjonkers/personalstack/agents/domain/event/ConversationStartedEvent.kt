package com.jorisjonkers.personalstack.agents.domain.event

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.common.event.DomainEvent
import java.time.Instant
import java.util.UUID

data class ConversationStartedEvent(
    val conversationId: ConversationId,
    val userId: UUID,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent

package com.jorisjonkers.personalstack.agents.domain.model

import java.util.UUID

@JvmInline
value class ConversationMessageId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): ConversationMessageId = ConversationMessageId(UUID.randomUUID())

        fun parse(s: String): ConversationMessageId = ConversationMessageId(UUID.fromString(s))
    }
}

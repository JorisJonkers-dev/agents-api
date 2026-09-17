package com.jorisjonkers.personalstack.agents.domain.model

import java.util.UUID

@JvmInline
value class ConversationId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): ConversationId = ConversationId(UUID.randomUUID())

        fun parse(s: String): ConversationId = ConversationId(UUID.fromString(s))
    }
}

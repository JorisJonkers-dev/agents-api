package com.jorisjonkers.personalstack.agents.domain.model

import java.util.UUID

@JvmInline
value class ChatSessionId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): ChatSessionId = ChatSessionId(UUID.randomUUID())

        fun parse(s: String): ChatSessionId = ChatSessionId(UUID.fromString(s))
    }
}

package com.jorisjonkers.personalstack.agents.domain.model

import java.util.UUID

@JvmInline
value class ChatMessageId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): ChatMessageId = ChatMessageId(UUID.randomUUID())

        fun parse(s: String): ChatMessageId = ChatMessageId(UUID.fromString(s))
    }
}

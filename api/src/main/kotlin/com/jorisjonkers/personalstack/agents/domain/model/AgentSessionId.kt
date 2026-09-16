package com.jorisjonkers.personalstack.agents.domain.model

import java.util.UUID

@JvmInline
value class AgentSessionId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): AgentSessionId = AgentSessionId(UUID.randomUUID())

        fun parse(s: String): AgentSessionId = AgentSessionId(UUID.fromString(s))
    }
}

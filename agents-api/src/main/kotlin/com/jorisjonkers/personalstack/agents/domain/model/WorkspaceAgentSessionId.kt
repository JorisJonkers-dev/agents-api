package com.jorisjonkers.personalstack.agents.domain.model

import java.util.UUID

@JvmInline
value class WorkspaceAgentSessionId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): WorkspaceAgentSessionId = WorkspaceAgentSessionId(UUID.randomUUID())

        fun parse(s: String): WorkspaceAgentSessionId = WorkspaceAgentSessionId(UUID.fromString(s))
    }
}

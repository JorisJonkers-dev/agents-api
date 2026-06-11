package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant
import java.util.UUID

@JvmInline
value class TurnId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): TurnId = TurnId(UUID.randomUUID())

        fun parse(s: String): TurnId = TurnId(UUID.fromString(s))
    }
}

enum class TurnRole { USER, AGENT, SYSTEM }

/**
 * An append-only entry in the per-session transcript. The body is
 * stored as a raw string in this PR; Step 7 layers the discriminated-
 * union Block protocol on top by parsing fenced JSON in the agent's
 * stream and ingesting one Turn per Block. Until then the entire
 * agent response is one big text Turn.
 */
data class Turn(
    val id: TurnId,
    val sessionId: WorkspaceAgentSessionId,
    val role: TurnRole,
    val body: String,
    val createdAt: Instant,
)

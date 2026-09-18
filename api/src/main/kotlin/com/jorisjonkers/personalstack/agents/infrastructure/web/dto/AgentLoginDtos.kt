package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.port.AgentLoginStore

/**
 * Whether a provider has an Agent Login available, so agents-ui can show the
 * sign-in hint next to an Agent Session rather than an error.
 *
 * Presence only. There is deliberately no field carrying, or derived from,
 * the login itself: under ADR 0002 the CLI owns its own credential end to
 * end and agents-api never reads it.
 */
data class AgentLoginResponse(
    val kind: String,
    val present: Boolean,
) {
    companion object {
        fun of(status: AgentLoginStore.AgentLoginStatus) =
            AgentLoginResponse(kind = status.kind.name.lowercase(), present = status.present)
    }
}

data class AgentLoginStatusResponse(
    val logins: List<AgentLoginResponse>,
) {
    companion object {
        fun of(statuses: List<AgentLoginStore.AgentLoginStatus>) =
            AgentLoginStatusResponse(logins = statuses.map(AgentLoginResponse::of))
    }
}

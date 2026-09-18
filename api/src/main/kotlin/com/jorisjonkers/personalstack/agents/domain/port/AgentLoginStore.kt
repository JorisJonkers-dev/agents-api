package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind

/**
 * Whether a provider has an Agent Login available to Agent Sessions.
 *
 * ADR 0002: an Agent Login is a property of the user, not of a Workspace.
 * The user signs in once from a terminal in an Agent Session, the CLI writes
 * its own login files under `$HOME`, and the home volume keeps them across
 * restarts. Nothing here captures, stores, validates or injects a
 * credential — the CLI owns its own login end to end, and this only reports
 * whether one is there, so agents-ui can show the sign-in hint instead of an
 * error.
 */
interface AgentLoginStore {
    /** Whether [kind]'s CLI has a login on the home volume. */
    fun isPresent(kind: WorkspaceAgentKind): Boolean

    /** Presence for every Agent Kind that has a provider login, in a stable order. */
    fun statuses(): List<AgentLoginStatus>

    data class AgentLoginStatus(
        val kind: WorkspaceAgentKind,
        val present: Boolean,
    )
}

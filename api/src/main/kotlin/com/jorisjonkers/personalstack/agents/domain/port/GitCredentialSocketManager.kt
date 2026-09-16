package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId

/**
 * Starts and stops the per-Workspace unix-socket credential service
 * (ADR 0003) that hands short-lived, Workspace-scoped GitHub App
 * installation tokens to Agent Sessions running in-container.
 * [ensureStarted] is idempotent; [stop] tears the listener down and
 * removes its socket file.
 */
interface GitCredentialSocketManager {
    fun ensureStarted(workspaceId: WorkspaceId)

    fun stop(workspaceId: WorkspaceId)
}

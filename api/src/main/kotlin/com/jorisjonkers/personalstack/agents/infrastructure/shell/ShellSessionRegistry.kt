package com.jorisjonkers.personalstack.agents.infrastructure.shell

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class ShellSession(
    val gatewayAgentId: String,
    val workspaceId: WorkspaceId,
    val tmuxSessionName: String,
    val cwd: String,
    val logFile: Path,
    val createdAt: Instant,
)

/**
 * In-memory registry of Shell Agent Sessions running in this container.
 * agent-gateway's registry was flat because one Pod ever served one
 * Workspace; one shared JVM now serving every Workspace makes that a
 * cross-Workspace hole, so every lookup here is scoped to a Workspace.
 * A gatewayAgentId that exists but belongs to a different Workspace is
 * indistinguishable from an id that was never spawned — both look up
 * as not-found, never as a permission error that would confirm the id
 * exists.
 */
@Component
class ShellSessionRegistry {
    private val sessions = ConcurrentHashMap<String, ShellSession>()

    fun put(session: ShellSession) {
        sessions[session.gatewayAgentId] = session
    }

    fun find(
        workspaceId: WorkspaceId,
        gatewayAgentId: String,
    ): ShellSession? = sessions[gatewayAgentId]?.takeIf { it.workspaceId == workspaceId }

    fun remove(
        workspaceId: WorkspaceId,
        gatewayAgentId: String,
    ): ShellSession? {
        val existing = find(workspaceId, gatewayAgentId) ?: return null
        return sessions.remove(gatewayAgentId, existing).let { if (it) existing else null }
    }
}

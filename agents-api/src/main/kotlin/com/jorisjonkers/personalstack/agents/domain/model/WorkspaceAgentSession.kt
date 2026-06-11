package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant

enum class WorkspaceAgentSessionStatus { STARTING, RUNNING, STOPPED, FAILED }

/**
 * One agent process inside a workspace's runner Pod. The
 * `gatewayAgentId` is what the agent-gateway hands back when we POST
 * /agents — the agents-api always addresses gateway resources by
 * that short id, never by our own UUID, because the gateway is the
 * source of truth for "is this process actually alive".
 *
 * `cliSessionId` is the native CLI session id returned by the
 * gateway at spawn time (Claude: from `--session-id <uuid>`;
 * Codex: captured after spawn, null until discovered; Shell: never
 * set). Stored for observability and future explicit continuation
 * flows; new session starts do not implicitly resume it.
 *
 * `runMode` is `INTERACTIVE` for browser-terminal sessions and will
 * be `HEADLESS` once N4 headless runs land.
 */
data class WorkspaceAgentSession(
    val id: WorkspaceAgentSessionId,
    val workspaceId: WorkspaceId,
    val kind: WorkspaceAgentKind,
    val gatewayAgentId: String?,
    val status: WorkspaceAgentSessionStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val cliSessionId: String? = null,
    val runMode: String = "INTERACTIVE",
) {
    fun bindGatewayAgent(
        gatewayAgentId: String,
        cliSessionId: String? = null,
    ): WorkspaceAgentSession =
        copy(
            gatewayAgentId = gatewayAgentId,
            cliSessionId = cliSessionId,
            status = WorkspaceAgentSessionStatus.RUNNING,
            updatedAt = Instant.now(),
        )

    fun markStopped(): WorkspaceAgentSession =
        copy(status = WorkspaceAgentSessionStatus.STOPPED, updatedAt = Instant.now())

    fun markFailed(): WorkspaceAgentSession =
        copy(status = WorkspaceAgentSessionStatus.FAILED, updatedAt = Instant.now())
}

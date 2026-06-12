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
    val epoch: Long = 1,
    val generation: Long = 0,
    val gatewayBoundAt: Instant? = null,
    val retainedUntil: Instant? = null,
    val cleanupRequestedAt: Instant? = null,
) {
    val stableSessionId: String get() = id.value.toString()

    fun bindGatewayAgent(
        gatewayAgentId: String,
        cliSessionId: String? = null,
        now: Instant = Instant.now(),
    ): WorkspaceAgentSession =
        copy(
            gatewayAgentId = gatewayAgentId,
            cliSessionId = cliSessionId,
            status = WorkspaceAgentSessionStatus.RUNNING,
            gatewayBoundAt = now,
            retainedUntil = null,
            cleanupRequestedAt = null,
            updatedAt = now,
        )

    fun beginGeneration(
        nextEpoch: Long = epoch + 1,
        now: Instant = Instant.now(),
    ): WorkspaceAgentSession =
        copy(
            gatewayAgentId = null,
            status = WorkspaceAgentSessionStatus.STARTING,
            epoch = nextEpoch,
            generation = generation + 1,
            gatewayBoundAt = null,
            retainedUntil = null,
            cleanupRequestedAt = null,
            updatedAt = now,
        )

    fun clearGatewayBinding(now: Instant = Instant.now()): WorkspaceAgentSession =
        copy(gatewayAgentId = null, gatewayBoundAt = null, updatedAt = now)

    fun markStopped(
        retainedUntil: Instant? = this.retainedUntil,
        now: Instant = Instant.now(),
    ): WorkspaceAgentSession =
        copy(
            gatewayAgentId = null,
            status = WorkspaceAgentSessionStatus.STOPPED,
            gatewayBoundAt = null,
            retainedUntil = retainedUntil,
            updatedAt = now,
        )

    fun markFailed(
        retainedUntil: Instant? = this.retainedUntil,
        now: Instant = Instant.now(),
    ): WorkspaceAgentSession =
        copy(
            gatewayAgentId = null,
            status = WorkspaceAgentSessionStatus.FAILED,
            gatewayBoundAt = null,
            retainedUntil = retainedUntil,
            updatedAt = now,
        )

    fun markCleanupRequested(now: Instant = Instant.now()): WorkspaceAgentSession =
        copy(cleanupRequestedAt = now, updatedAt = now)
}

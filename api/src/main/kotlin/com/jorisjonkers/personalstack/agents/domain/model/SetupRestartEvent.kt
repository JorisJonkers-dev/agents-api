package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant
import java.util.UUID

enum class SetupRestartEventStatus { REQUESTED, STARTED, COMPLETED, FAILED, CANCELLED }

data class SetupRestartEvent(
    val id: UUID,
    val workspaceId: WorkspaceId,
    val sessionId: WorkspaceAgentSessionId?,
    val fromSetupId: AgentSetupId?,
    val fromSetupVersion: AgentSetupVersion?,
    val toSetupId: AgentSetupId,
    val toSetupVersion: AgentSetupVersion,
    val status: SetupRestartEventStatus,
    val reason: String?,
    val message: String?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require((fromSetupId == null) == (fromSetupVersion == null)) {
            "from setup id and version must be set together"
        }
    }

    fun markStarted(now: Instant = Instant.now()): SetupRestartEvent =
        copy(status = SetupRestartEventStatus.STARTED, startedAt = now, updatedAt = now)

    fun markCompleted(now: Instant = Instant.now()): SetupRestartEvent =
        copy(status = SetupRestartEventStatus.COMPLETED, completedAt = now, updatedAt = now)

    fun markFailed(
        message: String?,
        now: Instant = Instant.now(),
    ): SetupRestartEvent =
        copy(status = SetupRestartEventStatus.FAILED, message = message, completedAt = now, updatedAt = now)
}

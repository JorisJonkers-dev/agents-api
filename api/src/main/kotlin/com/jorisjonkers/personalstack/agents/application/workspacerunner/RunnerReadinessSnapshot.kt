package com.jorisjonkers.personalstack.agents.application.workspacerunner

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import java.time.Instant
import java.util.UUID

sealed interface RunnerReadinessState {
    data object Ready : RunnerReadinessState

    data class Booting(
        val leaseId: UUID,
        val attempt: Int,
        val startedAt: Instant,
    ) : RunnerReadinessState

    data class Unavailable(
        val reason: RunnerUnavailableReason,
        val since: Instant? = null,
    ) : RunnerReadinessState
}

data class RunnerReadinessSnapshot(
    val workspaceId: WorkspaceId,
    val setupId: AgentSetupId,
    val setupVersion: AgentSetupVersion,
    val state: RunnerReadinessState,
    val checkedAt: Instant,
)

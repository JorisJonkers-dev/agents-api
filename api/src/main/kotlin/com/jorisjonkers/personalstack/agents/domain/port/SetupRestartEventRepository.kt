package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import java.time.Instant
import java.util.UUID

interface SetupRestartEventRepository {
    data class StatusUpdate(
        val id: UUID,
        val expectedStatus: SetupRestartEventStatus,
        val status: SetupRestartEventStatus,
        val message: String? = null,
        val startedAt: Instant? = null,
        val completedAt: Instant? = null,
        val now: Instant = Instant.now(),
    )

    fun save(event: SetupRestartEvent): SetupRestartEvent

    fun findById(id: UUID): SetupRestartEvent?

    fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<SetupRestartEvent>

    fun findAllBySessionId(sessionId: WorkspaceAgentSessionId): List<SetupRestartEvent>

    fun updateStatusIfCurrent(update: StatusUpdate): Boolean
}

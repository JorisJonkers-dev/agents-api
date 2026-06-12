package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import java.time.Instant
import java.util.UUID

interface SetupRestartEventRepository {
    fun save(event: SetupRestartEvent): SetupRestartEvent

    fun findById(id: UUID): SetupRestartEvent?

    fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<SetupRestartEvent>

    fun findAllBySessionId(sessionId: WorkspaceAgentSessionId): List<SetupRestartEvent>

    fun updateStatusIfCurrent(
        id: UUID,
        expectedStatus: SetupRestartEventStatus,
        status: SetupRestartEventStatus,
        message: String? = null,
        startedAt: Instant? = null,
        completedAt: Instant? = null,
        now: Instant = Instant.now(),
    ): Boolean
}

package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId

interface WorkspaceAgentSessionRepository {
    fun save(session: WorkspaceAgentSession): WorkspaceAgentSession

    fun findById(id: WorkspaceAgentSessionId): WorkspaceAgentSession?

    fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<WorkspaceAgentSession>

    fun delete(id: WorkspaceAgentSessionId)
}

package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus

interface WorkspaceRepository {
    fun save(workspace: Workspace): Workspace

    fun findById(id: WorkspaceId): Workspace?

    fun findAllByStatusNot(status: WorkspaceStatus): List<Workspace>

    fun delete(id: WorkspaceId)
}

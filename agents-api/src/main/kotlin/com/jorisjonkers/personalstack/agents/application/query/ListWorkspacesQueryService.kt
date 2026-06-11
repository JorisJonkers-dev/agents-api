package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.springframework.stereotype.Service

@Service
class ListWorkspacesQueryService(
    private val repo: WorkspaceRepository,
) {
    fun listActive(): List<Workspace> = repo.findAllByStatusNot(WorkspaceStatus.DESTROYED)
}

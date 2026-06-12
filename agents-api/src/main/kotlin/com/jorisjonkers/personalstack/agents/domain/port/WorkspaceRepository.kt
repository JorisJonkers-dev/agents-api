package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import java.time.Instant

interface WorkspaceRepository {
    fun save(workspace: Workspace): Workspace

    fun findById(id: WorkspaceId): Workspace?

    fun findAllByStatusNot(status: WorkspaceStatus): List<Workspace>

    fun beginRunnerSetupOperation(
        id: WorkspaceId,
        expectedGeneration: Long,
        setupId: AgentSetupId,
        setupVersion: AgentSetupVersion,
        operation: RunnerSetupOperation = RunnerSetupOperation.RESTARTING,
        now: Instant = Instant.now(),
    ): Boolean

    fun completeRunnerSetupOperation(
        id: WorkspaceId,
        expectedGeneration: Long,
        now: Instant = Instant.now(),
    ): Boolean

    fun failRunnerSetupOperation(
        id: WorkspaceId,
        expectedGeneration: Long,
        now: Instant = Instant.now(),
    ): Boolean

    fun delete(id: WorkspaceId)
}

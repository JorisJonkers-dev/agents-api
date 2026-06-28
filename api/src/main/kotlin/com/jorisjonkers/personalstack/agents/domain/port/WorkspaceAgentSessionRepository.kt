package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import java.time.Instant

interface WorkspaceAgentSessionRepository {
    fun save(session: WorkspaceAgentSession): WorkspaceAgentSession

    fun findById(id: WorkspaceAgentSessionId): WorkspaceAgentSession?

    fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<WorkspaceAgentSession>

    fun beginGeneration(
        id: WorkspaceAgentSessionId,
        expectedGeneration: Long,
        nextEpoch: Long,
        now: Instant = Instant.now(),
    ): Boolean

    fun bindIfGeneration(
        id: WorkspaceAgentSessionId,
        expectedGeneration: Long,
        gatewayAgentId: String,
        cliSessionId: String?,
        now: Instant = Instant.now(),
    ): Boolean

    fun clearGatewayBindingIfGeneration(
        id: WorkspaceAgentSessionId,
        expectedGeneration: Long,
        now: Instant = Instant.now(),
    ): Boolean

    fun markLifecycleIfGeneration(
        id: WorkspaceAgentSessionId,
        expectedGeneration: Long,
        status: WorkspaceAgentSessionStatus,
        retainedUntil: Instant?,
        clearGatewayBinding: Boolean,
        now: Instant = Instant.now(),
    ): Boolean

    fun findReadyForCleanup(
        now: Instant,
        limit: Int,
    ): List<WorkspaceAgentSession>

    fun markCleanupRequested(
        id: WorkspaceAgentSessionId,
        now: Instant = Instant.now(),
    ): Boolean

    fun setPendingSetupIfCurrent(
        id: WorkspaceAgentSessionId,
        expectedCurrentSetupId: AgentSetupId,
        expectedCurrentSetupVersion: AgentSetupVersion,
        pendingSetupId: AgentSetupId,
        pendingSetupVersion: AgentSetupVersion,
        now: Instant = Instant.now(),
    ): Boolean

    fun promotePendingSetupIfCurrent(
        id: WorkspaceAgentSessionId,
        expectedPendingSetupId: AgentSetupId,
        expectedPendingSetupVersion: AgentSetupVersion,
        now: Instant = Instant.now(),
    ): Boolean

    fun clearPendingSetupIfCurrent(
        id: WorkspaceAgentSessionId,
        expectedPendingSetupId: AgentSetupId,
        expectedPendingSetupVersion: AgentSetupVersion,
        now: Instant = Instant.now(),
    ): Boolean

    fun findCleanupRequested(limit: Int): List<WorkspaceAgentSession>

    fun delete(id: WorkspaceAgentSessionId): Boolean
}

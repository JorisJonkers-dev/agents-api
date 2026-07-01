package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import java.time.Instant

interface WorkspaceAgentSessionRepository {
    data class LifecycleUpdate(
        val id: WorkspaceAgentSessionId,
        val expectedGeneration: Long,
        val status: WorkspaceAgentSessionStatus,
        val retainedUntil: Instant?,
        val clearGatewayBinding: Boolean,
        val now: Instant = Instant.now(),
    )

    data class PendingSetupUpdate(
        val id: WorkspaceAgentSessionId,
        val expectedCurrentSetupId: AgentSetupId,
        val expectedCurrentSetupVersion: AgentSetupVersion,
        val pendingSetupId: AgentSetupId,
        val pendingSetupVersion: AgentSetupVersion,
        val now: Instant = Instant.now(),
    )

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

    fun markLifecycleIfGeneration(update: LifecycleUpdate): Boolean

    fun findReadyForCleanup(
        now: Instant,
        limit: Int,
    ): List<WorkspaceAgentSession>

    fun markCleanupRequested(
        id: WorkspaceAgentSessionId,
        now: Instant = Instant.now(),
    ): Boolean

    fun setPendingSetupIfCurrent(update: PendingSetupUpdate): Boolean

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

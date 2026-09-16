package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import java.time.Instant

interface AgentSessionRepository {
    data class LifecycleUpdate(
        val id: AgentSessionId,
        val expectedGeneration: Long,
        val status: AgentSessionStatus,
        val retainedUntil: Instant?,
        val clearGatewayBinding: Boolean,
        val now: Instant = Instant.now(),
    )

    data class PendingSetupUpdate(
        val id: AgentSessionId,
        val expectedCurrentSetupId: AgentSetupId,
        val expectedCurrentSetupVersion: AgentSetupVersion,
        val pendingSetupId: AgentSetupId,
        val pendingSetupVersion: AgentSetupVersion,
        val now: Instant = Instant.now(),
    )

    fun save(session: AgentSession): AgentSession

    fun findById(id: AgentSessionId): AgentSession?

    fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<AgentSession>

    fun beginGeneration(
        id: AgentSessionId,
        expectedGeneration: Long,
        nextEpoch: Long,
        now: Instant = Instant.now(),
    ): Boolean

    fun bindIfGeneration(
        id: AgentSessionId,
        expectedGeneration: Long,
        gatewayAgentId: String,
        cliSessionId: String?,
        now: Instant = Instant.now(),
    ): Boolean

    fun clearGatewayBindingIfGeneration(
        id: AgentSessionId,
        expectedGeneration: Long,
        now: Instant = Instant.now(),
    ): Boolean

    fun markLifecycleIfGeneration(update: LifecycleUpdate): Boolean

    fun findReadyForCleanup(
        now: Instant,
        limit: Int,
    ): List<AgentSession>

    fun markCleanupRequested(
        id: AgentSessionId,
        now: Instant = Instant.now(),
    ): Boolean

    fun setPendingSetupIfCurrent(update: PendingSetupUpdate): Boolean

    fun promotePendingSetupIfCurrent(
        id: AgentSessionId,
        expectedPendingSetupId: AgentSetupId,
        expectedPendingSetupVersion: AgentSetupVersion,
        now: Instant = Instant.now(),
    ): Boolean

    fun clearPendingSetupIfCurrent(
        id: AgentSessionId,
        expectedPendingSetupId: AgentSetupId,
        expectedPendingSetupVersion: AgentSetupVersion,
        now: Instant = Instant.now(),
    ): Boolean

    fun findCleanupRequested(limit: Int): List<AgentSession>

    fun delete(id: AgentSessionId): Boolean
}

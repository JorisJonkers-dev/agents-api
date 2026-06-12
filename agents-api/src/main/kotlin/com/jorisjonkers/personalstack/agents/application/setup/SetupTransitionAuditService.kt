package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.port.SetupRestartEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class SetupTransitionAuditService(
    private val events: SetupRestartEventRepository,
) {
    @Transactional
    fun recordRejected(
        workspace: Workspace,
        session: WorkspaceAgentSession?,
        targetId: AgentSetupId,
        targetVersion: AgentSetupVersion,
        result: AgentSetupValidationResult,
        now: Instant = Instant.now(),
    ): SetupRestartEvent? {
        if (result.valid) return null
        val fromSetupId = session?.currentSetupId ?: workspace.currentRunnerSetupId
        val fromSetupVersion = session?.currentSetupVersion ?: workspace.currentRunnerSetupVersion
        return events.save(
            SetupRestartEvent(
                id = UUID.randomUUID(),
                workspaceId = workspace.id,
                sessionId = session?.id,
                fromSetupId = fromSetupId,
                fromSetupVersion = fromSetupVersion,
                toSetupId = targetId,
                toSetupVersion = targetVersion,
                status = SetupRestartEventStatus.FAILED,
                reason = REASON_VALIDATION_REJECTED,
                message = result.message(),
                requestedAt = now,
                startedAt = null,
                completedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    companion object {
        const val REASON_VALIDATION_REJECTED = "validation-rejected"
    }
}

package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class RestartAgentSessionService(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val binding: RunnerSessionBindingService,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun restart(request: RestartAgentSessionInput): RunnerSessionBindingResult {
        workspaces.findById(request.workspaceId)
            ?: throw NoSuchElementException("workspace not found: ${request.workspaceId.value}")
        val session =
            sessions.findById(request.sessionId)
                ?: throw NoSuchElementException("session not found: ${request.sessionId.value}")
        require(session.workspaceId == request.workspaceId) {
            "session does not belong to workspace: ${request.sessionId.value}"
        }
        require(session.runMode == INTERACTIVE_RUN_MODE) {
            "session is not interactive: ${request.sessionId.value}"
        }
        require(session.isRestartable(clock.instant())) {
            "session cannot be restarted: ${request.sessionId.value}"
        }

        val expectedGeneration = request.expectedGeneration ?: session.generation
        if (expectedGeneration != session.generation) {
            return RunnerSessionBindingResult.Conflict(current = session)
        }
        return binding.restart(
            RestartRunnerSessionBindingInput(
                workspaceId = request.workspaceId,
                sessionId = request.sessionId,
                expectedGeneration = expectedGeneration,
                reason = request.reason,
            ),
        )
    }

    private fun WorkspaceAgentSession.isRestartable(now: Instant): Boolean =
        when (status) {
            WorkspaceAgentSessionStatus.RUNNING,
            WorkspaceAgentSessionStatus.FAILED,
            -> true

            WorkspaceAgentSessionStatus.STOPPED ->
                retainedUntil?.isAfter(now) == true && cleanupRequestedAt == null

            WorkspaceAgentSessionStatus.STARTING -> false
        }

    companion object {
        const val INTERACTIVE_RUN_MODE = "INTERACTIVE"
    }
}

data class RestartAgentSessionInput(
    val workspaceId: WorkspaceId,
    val sessionId: WorkspaceAgentSessionId,
    val expectedGeneration: Long? = null,
    val reason: String? = "restart",
)

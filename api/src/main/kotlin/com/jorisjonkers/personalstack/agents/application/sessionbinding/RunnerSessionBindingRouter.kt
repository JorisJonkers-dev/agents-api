package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * Picks the [RunnerSessionBindingService] the same way
 * [com.jorisjonkers.personalstack.agents.infrastructure.integration.AgentGatewayClientRouter]
 * picks a gateway: a Shell Agent Session in a Scratch Workspace binds
 * in-container; everything else keeps going through [RunnerSessionBinder]
 * and its Pod provisioning.
 */
@Primary
@Component
class RunnerSessionBindingRouter(
    private val podBinding: RunnerSessionBinder,
    private val inContainerBinding: InContainerSessionBindingService,
    private val workspaces: WorkspaceRepository,
    private val sessions: AgentSessionRepository,
) : RunnerSessionBindingService {
    override fun start(request: StartRunnerSessionBindingInput): RunnerSessionBindingResult {
        val workspace = workspaces.findById(request.workspaceId) ?: return podBinding.start(request)
        return targetFor(workspace.kind, request.kind).start(request)
    }

    override fun restart(request: RestartRunnerSessionBindingInput): RunnerSessionBindingResult =
        (targetForExisting(request.workspaceId, request.sessionId) ?: podBinding).restart(request)

    override fun ensureBound(request: EnsureRunnerSessionBoundInput): RunnerSessionBindingResult {
        val session = sessions.findById(request.sessionId) ?: return podBinding.ensureBound(request)
        val workspaceId = request.workspaceId ?: session.workspaceId
        return (targetForExisting(workspaceId, session) ?: podBinding).ensureBound(request)
    }

    private fun targetForExisting(
        workspaceId: WorkspaceId,
        sessionId: AgentSessionId,
    ): RunnerSessionBindingService? {
        val session = sessions.findById(sessionId) ?: return null
        return targetForExisting(workspaceId, session)
    }

    private fun targetForExisting(
        workspaceId: WorkspaceId,
        session: AgentSession,
    ): RunnerSessionBindingService? {
        val workspace = workspaces.findById(workspaceId) ?: return null
        return targetFor(workspace.kind, session.kind)
    }

    private fun targetFor(
        workspaceKind: WorkspaceKind,
        agentKind: WorkspaceAgentKind,
    ): RunnerSessionBindingService =
        if (workspaceKind == WorkspaceKind.SCRATCH && agentKind == WorkspaceAgentKind.SHELL) {
            inContainerBinding
        } else {
            podBinding
        }
}

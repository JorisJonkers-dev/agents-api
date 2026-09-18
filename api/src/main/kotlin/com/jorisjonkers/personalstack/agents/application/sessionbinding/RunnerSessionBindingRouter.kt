package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * Picks the [RunnerSessionBindingService] the same way
 * [com.jorisjonkers.personalstack.agents.infrastructure.integration.AgentGatewayClientRouter]
 * picks a gateway: any Agent Session in a Scratch Workspace binds
 * in-container; a Repo-backed Workspace keeps going through
 * [RunnerSessionBinder] and its Pod provisioning.
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
        return targetFor(workspace).start(request)
    }

    override fun restart(request: RestartRunnerSessionBindingInput): RunnerSessionBindingResult =
        (targetForExisting(request.workspaceId, request.sessionId) ?: podBinding).restart(request)

    override fun ensureBound(request: EnsureRunnerSessionBoundInput): RunnerSessionBindingResult {
        val session = sessions.findById(request.sessionId) ?: return podBinding.ensureBound(request)
        val workspaceId = request.workspaceId ?: session.workspaceId
        return (targetForWorkspace(workspaceId) ?: podBinding).ensureBound(request)
    }

    // The session lookup is a guard, not a routing input: since #64 the Agent
    // Kind no longer picks the binder, but a request naming a session that does
    // not exist still belongs to the Pod binder, which is where that error is
    // already shaped.
    private fun targetForExisting(
        workspaceId: WorkspaceId,
        sessionId: AgentSessionId,
    ): RunnerSessionBindingService? {
        sessions.findById(sessionId) ?: return null
        return targetForWorkspace(workspaceId)
    }

    private fun targetForWorkspace(workspaceId: WorkspaceId): RunnerSessionBindingService? {
        val workspace = workspaces.findById(workspaceId) ?: return null
        return targetFor(workspace)
    }

    // Every Agent Kind in a Scratch Workspace, not just Shell (#64): Claude and
    // Codex read their Agent Login from the home volume, which only exists in
    // this container. A Repo-backed Workspace still needs the Pod entrypoint's
    // clone, so it stays on the Pod path until #67.
    //
    // podName is the second half of that test, and it is not redundant. A
    // Scratch Workspace created before #62 can still hold a Claude or Codex
    // session bound to a runner Pod, and those rows survive this deploy.
    // Routing one here would send restart() into an UnsupportedOperationException
    // and ensureBound() into the in-container workspace directory instead of the
    // Pod's /workspace -- stranding a session that was working. A Workspace this
    // container binds never provisions a Pod, so its podName stays null.
    private fun targetFor(workspace: Workspace): RunnerSessionBindingService =
        if (workspace.kind == WorkspaceKind.SCRATCH && workspace.podName == null) {
            inContainerBinding
        } else {
            podBinding
        }
}

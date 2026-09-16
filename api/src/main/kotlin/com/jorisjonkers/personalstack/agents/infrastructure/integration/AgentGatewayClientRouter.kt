package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellSessionRegistry
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Picks the [AgentGatewayClient] implementation by where the Agent
 * Session actually runs. For an operation naming a session, the
 * in-container registry is the authority: it holds exactly the sessions
 * this container spawned, so a session it does not know is by
 * definition one the runner Pod owns. Routing on the Workspace alone
 * would be wrong — a Scratch Workspace can still hold a Claude or Codex
 * Agent Session bound to a Pod before #62, and sending its stop or
 * input in-container would silently drop it and orphan the process.
 */
@Primary
@Component
class AgentGatewayClientRouter(
    private val podGateway: HttpAgentGatewayClient,
    private val inContainerGateway: InContainerAgentGatewayClient,
    private val registry: ShellSessionRegistry,
) : AgentGatewayClient {
    override fun spawnAgent(request: AgentGatewayClient.SpawnAgentRequest): AgentGatewayClient.GatewayAgent =
        target(request.workspace, request.kind).spawnAgent(request)

    override fun stopAgent(
        workspace: Workspace,
        gatewayAgentId: String,
    ) = target(workspace, gatewayAgentId).stopAgent(workspace, gatewayAgentId)

    override fun cleanupStableSession(
        workspace: Workspace,
        stableSessionId: AgentSessionId,
    ) = target(workspace).cleanupStableSession(workspace, stableSessionId)

    override fun sendInput(
        workspace: Workspace,
        gatewayAgentId: String,
        input: String,
        enter: Boolean,
    ) = target(workspace, gatewayAgentId).sendInput(workspace, gatewayAgentId, input, enter)

    override fun stageInput(
        workspace: Workspace,
        gatewayAgentId: String,
        content: String,
        name: String?,
    ): AgentGatewayClient.StagedInput =
        target(workspace, gatewayAgentId).stageInput(workspace, gatewayAgentId, content, name)

    override fun capture(
        workspace: Workspace,
        gatewayAgentId: String,
    ): String = target(workspace, gatewayAgentId).capture(workspace, gatewayAgentId)

    override fun clone(
        workspace: Workspace,
        repoUrl: String,
        branch: String?,
    ): String = target(workspace).clone(workspace, repoUrl, branch)

    override fun openPr(
        workspace: Workspace,
        repoDir: String,
        title: String,
        body: String,
        base: String,
    ): String = target(workspace).openPr(workspace, repoDir, title, body, base)

    override fun isReady(workspace: Workspace): Boolean = target(workspace).isReady(workspace)

    override fun agentIdle(
        workspace: Workspace,
        gatewayAgentId: String,
    ): Duration? = target(workspace, gatewayAgentId).agentIdle(workspace, gatewayAgentId)

    override fun startHeadlessJob(request: AgentGatewayClient.HeadlessJobRequest): AgentGatewayClient.HeadlessJob =
        target(request.workspace).startHeadlessJob(request)

    override fun pollHeadlessJob(
        workspace: Workspace,
        headlessJobId: String,
    ): AgentGatewayClient.HeadlessJob = target(workspace).pollHeadlessJob(workspace, headlessJobId)

    /** Spawn has no session yet, so it routes on the Agent Kind it is about to start. */
    private fun target(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
    ): AgentGatewayClient =
        if (workspace.kind == WorkspaceKind.SCRATCH && kind == WorkspaceAgentKind.SHELL) {
            inContainerGateway
        } else {
            podGateway
        }

    private fun target(
        workspace: Workspace,
        gatewayAgentId: String,
    ): AgentGatewayClient = if (registry.find(workspace.id, gatewayAgentId) != null) inContainerGateway else podGateway

    /** Workspace-wide, no session named: only a Scratch Workspace runs in this container. */
    private fun target(workspace: Workspace): AgentGatewayClient =
        if (workspace.kind == WorkspaceKind.SCRATCH) inContainerGateway else podGateway
}

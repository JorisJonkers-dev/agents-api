package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Picks the [AgentGatewayClient] implementation by where the Agent
 * Session actually runs: a Scratch Workspace has no runner Pod, so any
 * operation on one can only be served by the in-container gateway — a
 * Scratch Workspace's [Workspace.gatewayEndpoint] is always null. Every
 * other Workspace keeps going through the runner Pod's HTTP gateway.
 */
@Primary
@Component
class AgentGatewayClientRouter(
    private val podGateway: HttpAgentGatewayClient,
    private val inContainerGateway: InContainerAgentGatewayClient,
) : AgentGatewayClient {
    override fun spawnAgent(request: AgentGatewayClient.SpawnAgentRequest): AgentGatewayClient.GatewayAgent =
        target(request.workspace, request.kind).spawnAgent(request)

    override fun stopAgent(
        workspace: Workspace,
        gatewayAgentId: String,
    ) = target(workspace).stopAgent(workspace, gatewayAgentId)

    override fun cleanupStableSession(
        workspace: Workspace,
        stableSessionId: AgentSessionId,
    ) = target(workspace).cleanupStableSession(workspace, stableSessionId)

    override fun sendInput(
        workspace: Workspace,
        gatewayAgentId: String,
        input: String,
        enter: Boolean,
    ) = target(workspace).sendInput(workspace, gatewayAgentId, input, enter)

    override fun stageInput(
        workspace: Workspace,
        gatewayAgentId: String,
        content: String,
        name: String?,
    ): AgentGatewayClient.StagedInput = target(workspace).stageInput(workspace, gatewayAgentId, content, name)

    override fun capture(
        workspace: Workspace,
        gatewayAgentId: String,
    ): String = target(workspace).capture(workspace, gatewayAgentId)

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
    ): Duration? = target(workspace).agentIdle(workspace, gatewayAgentId)

    override fun startHeadlessJob(request: AgentGatewayClient.HeadlessJobRequest): AgentGatewayClient.HeadlessJob =
        target(request.workspace).startHeadlessJob(request)

    override fun pollHeadlessJob(
        workspace: Workspace,
        headlessJobId: String,
    ): AgentGatewayClient.HeadlessJob = target(workspace).pollHeadlessJob(workspace, headlessJobId)

    private fun target(
        workspace: Workspace,
        kind: WorkspaceAgentKind? = null,
    ): AgentGatewayClient =
        if (workspace.kind == WorkspaceKind.SCRATCH && (kind == null || kind == WorkspaceAgentKind.SHELL)) {
            inContainerGateway
        } else {
            podGateway
        }
}

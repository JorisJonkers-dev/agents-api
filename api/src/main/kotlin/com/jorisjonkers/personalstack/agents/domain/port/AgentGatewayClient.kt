package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import java.time.Duration

/**
 * Driven port: the HTTP/WS facade in front of an agent-gateway
 * running inside a workspace's runner Pod. Methods are the small
 * intersection of operations agents-api actually needs — anything
 * more granular is invoked by the agent itself via its tooling,
 * not by this control plane.
 */
interface AgentGatewayClient {
    data class GatewayAgent(
        val id: String,
        val kind: WorkspaceAgentKind,
        val cwd: String,
        val cliSessionId: String? = null,
        val stableSessionId: String? = null,
        val epoch: Long = 1,
        val continuation: ContinuationMetadata? = null,
    )

    data class ContinuationMetadata(
        val reason: String? = null,
        val previousEpoch: Long? = null,
        val fromSetupLabel: String? = null,
        val toSetupLabel: String? = null,
    )

    data class StagedInput(
        val path: String,
        val bytes: Long,
        val name: String,
    )

    data class SpawnAgentRequest(
        val workspace: Workspace,
        val kind: WorkspaceAgentKind,
        val workspacePath: String? = null,
        val stableSessionId: WorkspaceAgentSessionId? = null,
        val epoch: Long? = null,
        val continuation: ContinuationMetadata? = null,
        // When set (session revival), the gateway resumes the prior CLI
        // conversation (`--resume <id>`) instead of starting blank.
        val resumeCliSessionId: String? = null,
    )

    fun spawnAgent(request: SpawnAgentRequest): GatewayAgent

    fun stopAgent(
        workspace: Workspace,
        gatewayAgentId: String,
    )

    fun cleanupStableSession(
        workspace: Workspace,
        stableSessionId: WorkspaceAgentSessionId,
    )

    fun sendInput(
        workspace: Workspace,
        gatewayAgentId: String,
        input: String,
        enter: Boolean = true,
    )

    fun stageInput(
        workspace: Workspace,
        gatewayAgentId: String,
        content: String,
        name: String?,
    ): StagedInput

    fun capture(
        workspace: Workspace,
        gatewayAgentId: String,
    ): String

    fun clone(
        workspace: Workspace,
        repoUrl: String,
        branch: String? = null,
    ): String

    fun openPr(
        workspace: Workspace,
        repoDir: String,
        title: String,
        body: String,
        base: String = "main",
    ): String

    fun isReady(workspace: Workspace): Boolean

    /**
     * Time since the gateway's agent last produced output, or null when it
     * can't be determined (gateway unreachable, agent unknown, no log yet).
     * Used to hold off recycling a runner until its agent is idle; callers
     * must treat null as "cannot confirm idle" and not recycle.
     */
    fun agentIdle(
        workspace: Workspace,
        gatewayAgentId: String,
    ): Duration?

    enum class HeadlessStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

    data class HeadlessJob(
        val id: String,
        val status: HeadlessStatus,
        val exitCode: Int?,
        val output: String?,
    )

    data class HeadlessJobRequest(
        val workspace: Workspace,
        val kind: WorkspaceAgentKind,
        val prompt: String,
        val cliSessionId: String? = null,
        val timeoutSeconds: Long? = null,
        val stableSessionId: WorkspaceAgentSessionId? = null,
        val epoch: Long? = null,
        val continuation: ContinuationMetadata? = null,
        /**
         * When false (the default), the gateway injects KB_AUTO_MCP_DISABLED=1
         * so the headless worker does not fire auto-KB recall/capture hooks.
         * Set to true only for runs that explicitly need KB hook access.
         */
        val enableKbHooks: Boolean = false,
    )

    fun startHeadlessJob(request: HeadlessJobRequest): HeadlessJob

    fun pollHeadlessJob(
        workspace: Workspace,
        headlessJobId: String,
    ): HeadlessJob
}

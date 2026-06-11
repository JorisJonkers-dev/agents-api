package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind

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
    )

    /**
     * Result of the gateway's `/git/verify` probe. `read` proves the
     * staged deploy key can `git ls-remote`; `write` is a
     * non-destructive throwaway-ref push/delete against an existing
     * commit. `detail` carries the gateway's human-readable message.
     */
    data class AccessVerification(
        val read: Boolean,
        val write: Boolean,
        val detail: String,
    )

    data class StagedInput(
        val path: String,
        val bytes: Long,
        val name: String,
    )

    /**
     * Ask the standing agent-gateway to verify deploy-key access to
     * [repoUrl] on [branch] (HEAD when null). Returns null when no
     * gateway base URL is configured or the call is inconclusive —
     * callers degrade gracefully rather than fail hard.
     */
    fun verifyAccess(
        repoUrl: String,
        branch: String?,
    ): AccessVerification?

    fun spawnAgent(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        workspacePath: String? = null,
    ): GatewayAgent

    fun stopAgent(
        workspace: Workspace,
        gatewayAgentId: String,
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

    enum class HeadlessStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

    data class HeadlessJob(
        val id: String,
        val status: HeadlessStatus,
        val exitCode: Int?,
        val output: String?,
    )

    fun startHeadlessJob(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        prompt: String,
        cliSessionId: String? = null,
        timeoutSeconds: Long? = null,
    ): HeadlessJob

    fun pollHeadlessJob(
        workspace: Workspace,
        headlessJobId: String,
    ): HeadlessJob
}

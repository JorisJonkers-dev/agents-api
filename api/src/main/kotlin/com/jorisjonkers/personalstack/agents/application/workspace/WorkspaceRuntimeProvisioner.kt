package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService.BootOutcome
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.GitCredentialSocketManager
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.InContainerAgentGatewayClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Gives a newly created Workspace somewhere to run: a Scratch Workspace
 * gets a directory on the workspaces volume and no Kubernetes API call.
 * A Repo-backed Workspace gets that same directory, its git-credential
 * socket, and a clone of its primary Repository (#63) *and* still boots
 * a runner Pod — Claude and Codex only run there until #67, so this is
 * a deliberate, transitional double clone onto two different volumes,
 * not a bug. Every path here is best-effort — the Workspace row is
 * already committed and stays visible regardless of what its runtime
 * does.
 */
@Component
class WorkspaceRuntimeProvisioner(
    private val lifecycleService: WorkspaceRunnerLifecycleService,
    private val directories: WorkspaceDirectoryService,
    private val gitCredentialSockets: GitCredentialSocketManager,
    private val workspaces: WorkspaceRepository,
    private val inContainerGateway: InContainerAgentGatewayClient,
) {
    private val log = LoggerFactory.getLogger(WorkspaceRuntimeProvisioner::class.java)

    fun provision(
        workspaceId: WorkspaceId,
        kind: WorkspaceKind,
    ) {
        if (kind == WorkspaceKind.SCRATCH) {
            provisionScratch(workspaceId)
            return
        }
        if (kind == WorkspaceKind.REPO_BACKED) {
            provisionInContainer(workspaceId)
        }
        // For REPO_BACKED this may re-decide Ready/Failed once the Pod boots —
        // deliberate: the Pod path is authoritative until #67 retires it.
        bootRunner(workspaceId)
    }

    // SCRATCH never boots a Pod, so this in-container step is the only
    // Preparing -> Ready/Failed signal it ever gets (#63). Directory/socket
    // creation runs unconditionally — unlike the repo-backed path it needs
    // no Workspace fields, so a lookup miss must not skip it.
    private fun provisionScratch(workspaceId: WorkspaceId) {
        val result =
            runCatching {
                directories.ensureCreated(workspaceId)
                gitCredentialSockets.ensureStarted(workspaceId)
            }.onFailure { log.warn("workspace {} directory/socket creation failed", workspaceId, it) }
        markOutcome(workspaceId, result, SCRATCH_SETUP_FAILED_REASON)
    }

    private fun provisionInContainer(workspaceId: WorkspaceId) {
        val workspace = workspaces.findById(workspaceId) ?: return
        val result =
            runCatching {
                directories.ensureCreated(workspaceId)
                gitCredentialSockets.ensureStarted(workspaceId)
                workspace.repoUrl?.let { repoUrl -> inContainerGateway.clone(workspace, repoUrl, workspace.branch) }
            }.onFailure { log.warn("workspace {} in-container directory/socket/clone failed", workspaceId, it) }
        workspaces.save(resultingWorkspace(workspace, result, IN_CONTAINER_SETUP_FAILED_REASON))
    }

    private fun markOutcome(
        workspaceId: WorkspaceId,
        result: Result<*>,
        failureReason: String,
    ) {
        val workspace = workspaces.findById(workspaceId) ?: return
        workspaces.save(resultingWorkspace(workspace, result, failureReason))
    }

    private fun resultingWorkspace(
        workspace: Workspace,
        result: Result<*>,
        failureReason: String,
    ) = if (result.isSuccess) workspace.markReady() else workspace.markFailed(failureReason)

    private fun bootRunner(workspaceId: WorkspaceId) {
        val outcome = runCatching { lifecycleService.boot(workspaceId, WorkspaceAgentKind.CLAUDE) }.getOrNull()
        when (outcome) {
            is BootOutcome.Ready ->
                log.info("workspace {} runner boot succeeded", workspaceId)
            is BootOutcome.Conflict ->
                log.warn("workspace {} boot conflict: {}", workspaceId, outcome.reason)
            null ->
                log.warn("workspace {} runner boot failed; workspace remains visible", workspaceId)
        }
    }
}

private const val SCRATCH_SETUP_FAILED_REASON = "workspace directory or credential socket setup failed"
private const val IN_CONTAINER_SETUP_FAILED_REASON =
    "workspace directory, credential socket, or repository clone failed"

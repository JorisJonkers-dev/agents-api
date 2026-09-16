package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService.BootOutcome
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
            runCatching {
                directories.ensureCreated(workspaceId)
                gitCredentialSockets.ensureStarted(workspaceId)
            }.onFailure { log.warn("workspace {} directory/socket creation failed", workspaceId, it) }
            return
        }
        if (kind == WorkspaceKind.REPO_BACKED) {
            provisionInContainer(workspaceId)
        }
        bootRunner(workspaceId)
    }

    private fun provisionInContainer(workspaceId: WorkspaceId) {
        val workspace = workspaces.findById(workspaceId) ?: return
        runCatching {
            directories.ensureCreated(workspaceId)
            gitCredentialSockets.ensureStarted(workspaceId)
            workspace.repoUrl?.let { repoUrl -> inContainerGateway.clone(workspace, repoUrl, workspace.branch) }
        }.onFailure { log.warn("workspace {} in-container directory/socket/clone failed", workspaceId, it) }
    }

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

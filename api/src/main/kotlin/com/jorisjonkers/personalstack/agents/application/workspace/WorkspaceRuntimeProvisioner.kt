package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService.BootOutcome
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.GitCredentialSocketManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Gives a newly created Workspace somewhere to run: a Scratch Workspace
 * gets a directory on the workspaces volume and no Kubernetes API call,
 * everything else still boots a runner Pod. Both paths are best-effort —
 * the Workspace row is already committed and stays visible either way.
 */
@Component
class WorkspaceRuntimeProvisioner(
    private val lifecycleService: WorkspaceRunnerLifecycleService,
    private val directories: WorkspaceDirectoryService,
    private val gitCredentialSockets: GitCredentialSocketManager,
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
        bootRunner(workspaceId)
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

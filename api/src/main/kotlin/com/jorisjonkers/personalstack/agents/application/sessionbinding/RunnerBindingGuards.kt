package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerSetupTarget
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession

/**
 * Guard checks shared by the runner session binding flows. Extracted from
 * RunnerSessionBinder to keep that class below the TooManyFunctions threshold.
 */
internal class RunnerBindingGuards(
    private val provisioning: RunnerProvisioningCoordinator,
) {
    fun hasSetupOperationInProgress(
        session: WorkspaceAgentSession,
        workspace: Workspace,
    ): Boolean = sessionHasPendingSetup(session) || workspaceSetupInProgress(workspace)

    // Returns a terminal guard result when the session or workspace is blocked, null otherwise.
    fun ensureBoundGuard(
        session: WorkspaceAgentSession,
        workspace: Workspace,
    ): RunnerSessionBindingResult? =
        when {
            sessionHasPendingSetup(session) -> RunnerSessionBindingResult.Conflict(current = session)
            workspaceSetupInProgress(workspace) ->
                RunnerSessionBindingResult.Unavailable(
                    workspaceId = workspace.id,
                    runnerStatus = RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS.label,
                )
            else -> null
        }

    fun checkBindingReadiness(
        workspace: Workspace,
        target: RunnerSetupTarget,
    ): RunnerSessionBindingResult.Unavailable? {
        val unavailableReason =
            when {
                workspaceSetupInProgress(workspace) -> RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS.label
                workspace.runnerBootLeaseId != null -> RunnerUnavailableReason.BOOT_LEASE_HELD.label
                !provisioning.isRunnerReadyFor(workspace, target) ->
                    RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label
                else -> null
            }
        return unavailableReason?.let {
            RunnerSessionBindingResult.Unavailable(workspaceId = workspace.id, runnerStatus = it)
        }
    }

    private fun sessionHasPendingSetup(session: WorkspaceAgentSession): Boolean =
        session.pendingSetupId != null || session.pendingSetupVersion != null

    private fun workspaceSetupInProgress(workspace: Workspace): Boolean =
        workspace.pendingRunnerSetupId != null ||
            workspace.pendingRunnerSetupVersion != null ||
            workspace.runnerSetupOperation != RunnerSetupOperation.IDLE
}

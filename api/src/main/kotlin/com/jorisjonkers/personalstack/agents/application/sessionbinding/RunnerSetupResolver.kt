package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupSelectionService
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationInput
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerSetupTarget
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession

/**
 * Resolves which agent setup entry (and provisioning spec) to use for a given
 * binding operation. Extracted from RunnerSessionBinder to keep that class
 * below the TooManyFunctions threshold.
 */
internal class RunnerSetupResolver(
    private val setupSelection: AgentSetupSelectionService,
    private val setupValidation: AgentSetupValidationService,
) {
    fun resolveNewSessionSetup(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        setupId: AgentSetupId?,
        setupVersion: AgentSetupVersion?,
    ): RunnerSetupTarget {
        if (setupId != null || setupVersion != null) {
            require(setupId != null && setupVersion != null) { "setup id and version must be supplied together" }
            return requireValidTarget(workspace, kind, null, setupSelection.requireSelectable(setupId, setupVersion))
        }
        val current =
            runCatching {
                val entry =
                    setupSelection.requireSelectable(
                        workspace.currentRunnerSetupId,
                        workspace.currentRunnerSetupVersion,
                    )
                val result =
                    setupValidation.validate(
                        AgentSetupValidationInput(
                            workspace = workspace,
                            targetId = entry.definition.id,
                            targetVersion = entry.definition.version,
                            agentKind = kind,
                        ),
                    )
                if (result.valid) RunnerSetupTarget(entry, RunnerSetupProvisioningSpec.from(entry.definition)) else null
            }.getOrNull()
        if (current != null) return current
        return requireValidTarget(workspace, kind, null, setupSelection.defaultSelectable())
    }

    fun resolveRestartSetup(
        workspace: Workspace,
        session: WorkspaceAgentSession,
        request: RestartRunnerSessionBindingInput,
    ): RunnerSetupTarget {
        require((request.targetSetupId == null) == (request.targetSetupVersion == null)) {
            "target setup id and version must be supplied together"
        }
        val targetId = request.targetSetupId ?: session.currentSetupId
        val targetVersion = request.targetSetupVersion ?: session.currentSetupVersion
        return requireValidTarget(
            workspace = workspace,
            kind = session.kind,
            session = session,
            entry = setupSelection.requireSelectable(targetId, targetVersion),
        )
    }

    fun resolveSessionSetup(
        workspace: Workspace,
        session: WorkspaceAgentSession,
    ): RunnerSetupTarget =
        requireValidTarget(
            workspace = workspace,
            kind = session.kind,
            session = session,
            entry = setupSelection.requireSelectable(session.currentSetupId, session.currentSetupVersion),
        )

    private fun requireValidTarget(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        session: WorkspaceAgentSession?,
        entry: AgentSetupCatalogEntry,
    ): RunnerSetupTarget {
        setupValidation.requireValid(
            AgentSetupValidationInput(
                workspace = workspace,
                targetId = entry.definition.id,
                targetVersion = entry.definition.version,
                session = session,
                agentKind = kind,
            ),
        )
        return RunnerSetupTarget(entry, RunnerSetupProvisioningSpec.from(entry.definition))
    }
}

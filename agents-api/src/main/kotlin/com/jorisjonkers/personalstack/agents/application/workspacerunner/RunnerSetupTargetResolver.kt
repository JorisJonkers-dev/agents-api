package com.jorisjonkers.personalstack.agents.application.workspacerunner

import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupSelectionService
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationInput
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import org.springframework.stereotype.Component

data class RunnerSetupTarget(
    val entry: AgentSetupCatalogEntry,
    val spec: RunnerSetupProvisioningSpec,
)

/**
 * Resolves which [RunnerSetupTarget] to use for a new boot or session start.
 * Extracted so both [WorkspaceRunnerLifecycleService] and
 * [com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBinder]
 * share the same fallback logic without duplication.
 */
@Component
class RunnerSetupTargetResolver(
    private val setupSelection: AgentSetupSelectionService,
    private val setupValidation: AgentSetupValidationService,
) {
    /**
     * Resolve the setup for a new session or boot. Explicit [setupId]/[setupVersion]
     * win; otherwise tries the workspace's current setup, falling back to the
     * catalog default when the current setup is invalid.
     */
    fun resolve(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        setupId: AgentSetupId? = null,
        setupVersion: AgentSetupVersion? = null,
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

    internal fun requireValidTarget(
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

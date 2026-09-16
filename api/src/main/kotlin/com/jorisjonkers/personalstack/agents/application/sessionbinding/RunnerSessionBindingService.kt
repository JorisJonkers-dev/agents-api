package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient

interface RunnerSessionBindingService {
    fun start(request: StartRunnerSessionBindingInput): RunnerSessionBindingResult

    fun restart(request: RestartRunnerSessionBindingInput): RunnerSessionBindingResult

    fun ensureBound(request: EnsureRunnerSessionBoundInput): RunnerSessionBindingResult
}

data class StartRunnerSessionBindingInput(
    val workspaceId: WorkspaceId,
    val sessionId: AgentSessionId,
    val kind: WorkspaceAgentKind,
    val setupId: AgentSetupId? = null,
    val setupVersion: AgentSetupVersion? = null,
    val runMode: String = "INTERACTIVE",
)

data class RestartRunnerSessionBindingInput(
    val workspaceId: WorkspaceId,
    val sessionId: AgentSessionId,
    val expectedGeneration: Long,
    val reason: String? = null,
    val targetSetupId: AgentSetupId? = null,
    val targetSetupVersion: AgentSetupVersion? = null,
)

data class EnsureRunnerSessionBoundInput(
    val sessionId: AgentSessionId,
    val workspaceId: WorkspaceId? = null,
)

sealed interface RunnerSessionBindingResult {
    data class Bound(
        val workspace: Workspace,
        val session: AgentSession,
        val gatewayAgent: AgentGatewayClient.GatewayAgent,
        val provisioning: RunnerProvisioningResult,
    ) : RunnerSessionBindingResult

    data class Conflict(
        val current: AgentSession?,
    ) : RunnerSessionBindingResult

    data class Unavailable(
        val workspaceId: WorkspaceId,
        val runnerStatus: String,
        val retryAfterSeconds: Int? = null,
    ) : RunnerSessionBindingResult
}

sealed interface RunnerProvisioningResult {
    object AlreadyReady : RunnerProvisioningResult

    data class Provisioned(
        val podName: String,
        val pvcName: String,
        val gatewayEndpoint: String,
    ) : RunnerProvisioningResult
}

package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.RunnerState
import com.jorisjonkers.personalstack.agents.domain.model.Workspace

/**
 * Driven port: cluster-level Pod lifecycle for a workspace. The
 * fabric8 implementation lives in `infrastructure/k8s/` and is the
 * only place that touches the Kubernetes API. Keeping this port
 * narrow (provision, scaleDown, destroy, lookup) makes the application
 * layer agnostic to the cluster backend.
 */
interface AgentRunnerOrchestrator {
    data class RunnerHandle(
        val podName: String,
        val pvcName: String,
        val gatewayEndpoint: String,
    )

    fun provision(workspace: Workspace): RunnerHandle

    fun provision(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
    ): RunnerHandle

    /**
     * Tear down the runner Pod and Service while leaving the workspace
     * PVC and per-workspace deploy-key Secret intact. Used by the idle
     * sweep so a follow-up [provision] can re-attach the same disk
     * without a re-clone.
     */
    fun scaleDown(workspace: Workspace)

    /**
     * Full teardown: removes the Pod, Service, workspace PVC, and
     * per-workspace deploy-key Secret. Call only when the workspace is
     * being permanently deleted.
     */
    fun destroy(workspace: Workspace)

    fun runnerState(workspace: Workspace): RunnerState?

    fun isReady(workspace: Workspace): Boolean

    fun isReady(
        workspace: Workspace,
        expectedIdentity: RunnerState.Identity,
    ): Boolean
}

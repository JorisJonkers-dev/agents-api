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

    /**
     * True when the workspace's runner Pod is running an image from an
     * older release than the one currently in force (the agents-api image
     * was rebuilt and redeployed since the runner was provisioned). The
     * idle sweep uses this to recycle a disconnected stale runner so it
     * comes back on the new image, without waiting for the idle timer.
     * Returns false when the target version can't be determined or the
     * runner is absent, so an unknown never triggers a recycle.
     *
     * Staleness is by agent-runner release version: a runner is stale when the
     * version tag its Pod is pinned to ([runnerImageVersion]) differs from the
     * current [targetRunnerImageVersion].
     */
    fun isRunnerImageStale(workspace: Workspace): Boolean

    /**
     * The agent-runner release version the workspace's runner Pod is running
     * (the version tag its image is pinned to, e.g. "v0.12.0"), or null when
     * there is no runner or its image carries no version tag. Surfaced to the
     * operator as the runner image version.
     */
    fun runnerImageVersion(workspace: Workspace): String?

    /**
     * The current target agent-runner release version — the release agents-api
     * itself is running, since the whole suite is built and published in
     * lockstep from one release-please version. New runners are pinned to this.
     * Null when the running release can't be determined (e.g. a local build),
     * in which case no upgrade is ever offered. A workspace whose
     * [runnerImageVersion] differs from this has an upgrade available.
     */
    fun targetRunnerImageVersion(): String?
}

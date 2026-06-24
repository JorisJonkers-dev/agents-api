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
     * Returns false when the current release can't be determined or the
     * runner is absent, so an unknown never triggers a recycle.
     *
     * As of the runner-image-upgrade feature this is also true when the
     * workspace runner's resolved agent-runner image digest is behind the
     * freshest digest observed across running runners (see
     * [runnerImageDigest] / [freshestRunnerImageDigest]).
     */
    fun isRunnerImageStale(workspace: Workspace): Boolean

    /**
     * The agent-runner image digest the workspace's runner Pod actually
     * resolved to at pull time, or null when there is no running runner or the
     * digest can't be read. Used to surface the runner image to the operator.
     */
    fun runnerImageDigest(workspace: Workspace): String?

    /**
     * The freshest agent-runner image digest observed among running runner
     * Pods in the namespace — the de-facto current target, since the most
     * recently provisioned runner pulled the current `:latest`. Null when no
     * running runner exists. A workspace whose [runnerImageDigest] differs from
     * this has an upgrade available.
     */
    fun freshestRunnerImageDigest(): String?
}

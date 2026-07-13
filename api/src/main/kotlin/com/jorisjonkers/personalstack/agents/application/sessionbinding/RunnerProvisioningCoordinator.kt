package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerSetupTarget
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory

/**
 * Coordinates runner provisioning: scale-down, provision, await readiness,
 * and record the reprovision telemetry. Extracted from RunnerSessionBinder to
 * keep that class below the TooManyFunctions threshold.
 */
internal class RunnerProvisioningCoordinator(
    private val orchestrator: AgentRunnerOrchestrator,
    private val gateway: AgentGatewayClient,
    private val workspaces: WorkspaceRepository,
    private val metrics: RunnerBindingMetrics,
    private val backoffInitialMs: Long,
) {
    private val log = LoggerFactory.getLogger(RunnerProvisioningCoordinator::class.java)

    fun forceProvisionAndWait(
        workspace: Workspace,
        target: RunnerSetupTarget,
        runnerGeneration: Long,
    ): RunnerReady {
        val startedAt = System.nanoTime()
        return runCatching { provisionAndBuildReady(workspace, target, runnerGeneration) }
            .onSuccess {
                metrics.recordReprovision(OutcomeLabel.SUCCESS, FailureReasonLabel.NONE, startedAt)
            }.onFailure { ex ->
                val reason = metrics.reasonClass(ex)
                metrics.recordReprovision(OutcomeLabel.FAILURE, reason, startedAt)
            }.getOrThrow()
    }

    fun isRunnerReadyFor(
        workspace: Workspace,
        target: RunnerSetupTarget,
    ): Boolean {
        val identity = target.spec.identity(workspace.runnerSetupGeneration)
        return orchestrator.isReady(workspace, identity) && gateway.isReady(workspace)
    }

    private fun provisionAndBuildReady(
        workspace: Workspace,
        target: RunnerSetupTarget,
        runnerGeneration: Long,
    ): RunnerReady {
        val handle =
            runCatching {
                orchestrator.scaleDown(workspace)
                orchestrator.provision(workspace, target.spec, runnerGeneration)
            }.getOrElse { ex ->
                log.warn("reprovision of workspace {} failed during scaleDown/provision", workspace.id.value, ex)
                throw AgentRunnerUnavailableException(
                    workspaceId = workspace.id,
                    runnerStatus = "ReprovisionFailed",
                    cause = ex,
                )
            }
        val repointed =
            workspace.withPodInfo(
                podName = handle.podName,
                pvcName = handle.pvcName,
                gatewayEndpoint = handle.gatewayEndpoint,
            )
        val saved = workspaces.save(repointed)
        log.info("re-provisioned runner for workspace {} as pod {}", workspace.id.value, handle.podName)
        return RunnerReady(
            workspace = saved,
            ready = awaitRunnerReady(saved, target, runnerGeneration),
            provisioning =
                RunnerProvisioningResult.Provisioned(
                    podName = handle.podName,
                    pvcName = handle.pvcName,
                    gatewayEndpoint = handle.gatewayEndpoint,
                ),
        )
    }

    private fun awaitRunnerReady(
        workspace: Workspace,
        target: RunnerSetupTarget,
        runnerGeneration: Long,
    ): Boolean {
        val identity = target.spec.identity(runnerGeneration)
        repeat(RunnerAgentSpawner.MAX_SPAWN_ATTEMPTS) { attempt ->
            if (orchestrator.isReady(workspace, identity) && gateway.isReady(workspace)) return true
            if (attempt < RunnerAgentSpawner.MAX_SPAWN_ATTEMPTS - 1) {
                val sleepMs = backoffInitialMs * (attempt + 1)
                if (sleepMs > 0) Thread.sleep(sleepMs)
            }
        }
        return false
    }
}

internal data class RunnerReady(
    val workspace: Workspace,
    // The runner was reprovisioned; `ready` is whether it became ready within
    // the synchronous window. When false the session is left awaiting rebind.
    val ready: Boolean,
    val provisioning: RunnerProvisioningResult,
)

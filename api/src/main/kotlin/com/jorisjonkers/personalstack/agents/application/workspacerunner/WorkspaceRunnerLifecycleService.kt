package com.jorisjonkers.personalstack.agents.application.workspacerunner

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Manages the pod-level lifecycle of workspace runners: boot (scale-down →
 * provision → readiness check) and periodic reconciliation of stale boot
 * leases left by crashed callers.
 *
 * A boot lease (runner_boot_lease_id) is acquired atomically before any
 * Kubernetes operation, ensuring only one caller can boot a given workspace
 * at a time.  Failed boots increment runner_boot_attempt so repeated
 * failures are observable.
 */
@Component
class WorkspaceRunnerLifecycleService(
    private val workspaces: WorkspaceRepository,
    private val orchestrator: AgentRunnerOrchestrator,
    private val targetResolver: RunnerSetupTargetResolver,
    private val readinessPublisher: RunnerReadinessPublisher,
    private val clock: Clock = Clock.systemUTC(),
    @param:Value("\${agent-runtime.runner-boot-lease-timeout-seconds:300}")
    private val bootLeaseTimeoutSeconds: Long = 300,
) {
    private val log = LoggerFactory.getLogger(WorkspaceRunnerLifecycleService::class.java)

    sealed interface BootOutcome {
        data class Ready(
            val workspace: Workspace,
            val provisioning: BootProvisioningOutcome,
        ) : BootOutcome

        data class Conflict(
            val reason: RunnerUnavailableReason,
        ) : BootOutcome
    }

    sealed interface BootProvisioningOutcome {
        data object AlreadyReady : BootProvisioningOutcome

        data class Provisioned(
            val podName: String,
            val pvcName: String,
            val gatewayEndpoint: String,
        ) : BootProvisioningOutcome
    }

    /**
     * Boot the workspace runner.  Acquires the boot lease before any K8s
     * operation; returns [BootOutcome.Conflict] immediately when a conflicting
     * operation is in progress.  On success the lease is released via
     * [WorkspaceRepository.completeBootLease]; on failure via
     * [WorkspaceRepository.failBootLease] (which increments the attempt counter).
     */
    fun boot(
        workspaceId: WorkspaceId,
        kind: WorkspaceAgentKind,
        setupId: AgentSetupId? = null,
        setupVersion: AgentSetupVersion? = null,
    ): BootOutcome {
        val workspace =
            workspaces.findById(workspaceId)
                ?: return BootOutcome.Conflict(RunnerUnavailableReason.WORKSPACE_NOT_FOUND)
        return bootWorkspace(workspace, kind, setupId, setupVersion)
    }

    private fun bootWorkspace(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        setupId: AgentSetupId?,
        setupVersion: AgentSetupVersion?,
    ): BootOutcome {
        val target = targetResolver.resolve(workspace, kind, setupId, setupVersion)

        val identity = target.spec.identity(workspace.runnerSetupGeneration)
        if (orchestrator.isReady(workspace, identity)) {
            publish(readySnapshot(workspace, target, clock.instant()))
            return BootOutcome.Ready(workspace, BootProvisioningOutcome.AlreadyReady)
        }

        bootConflict(workspace)?.let { return it }

        val leaseId = UUID.randomUUID()
        return if (workspaces.acquireBootLease(workspace.id, leaseId)) {
            provision(workspace, target, leaseId)
        } else {
            BootOutcome.Conflict(RunnerUnavailableReason.BOOT_LEASE_HELD)
        }
    }

    private fun bootConflict(workspace: Workspace): BootOutcome.Conflict? =
        when {
            workspace.runnerSetupOperation != RunnerSetupOperation.IDLE ||
                workspace.pendingRunnerSetupId != null ->
                BootOutcome.Conflict(RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS)
            workspace.runnerBootLeaseId != null -> BootOutcome.Conflict(RunnerUnavailableReason.BOOT_LEASE_HELD)
            else -> null
        }

    data class RunnerImageStatus(
        val version: String?,
        val upgradeAvailable: Boolean,
    )

    /**
     * The workspace runner's current agent-runner release version and whether a
     * newer one is available (the runner is behind the release agents-api is
     * on). Best-effort: any cluster read failure degrades to
     * `version=null, upgradeAvailable=false` so it never blocks the response.
     */
    fun runnerImageStatus(workspace: Workspace): RunnerImageStatus {
        val current = runCatching { orchestrator.runnerImageVersion(workspace) }.getOrNull()
        val target = runCatching { orchestrator.targetRunnerImageVersion() }.getOrNull()
        return RunnerImageStatus(
            version = current,
            upgradeAvailable = current != null && target != null && current != target,
        )
    }

    /**
     * Return a readiness snapshot from persisted state.  When a boot lease
     * is held the state is [RunnerReadinessState.Booting]; otherwise
     * orchestrator readiness is checked live.
     */
    fun readinessSnapshot(workspace: Workspace): RunnerReadinessSnapshot {
        val now = clock.instant()
        val leaseId = workspace.runnerBootLeaseId
        if (leaseId != null) return bootingSnapshot(workspace, leaseId, now)
        val ready = runCatching { orchestrator.isReady(workspace) }.getOrDefault(false)
        val state = currentReadinessState(workspace, ready)
        return RunnerReadinessSnapshot(
            workspaceId = workspace.id,
            setupId = workspace.currentRunnerSetupId,
            setupVersion = workspace.currentRunnerSetupVersion,
            state = state,
            checkedAt = now,
        )
    }

    private fun bootingSnapshot(
        workspace: Workspace,
        leaseId: UUID,
        now: Instant,
    ): RunnerReadinessSnapshot =
        RunnerReadinessSnapshot(
            workspaceId = workspace.id,
            setupId = workspace.currentRunnerSetupId,
            setupVersion = workspace.currentRunnerSetupVersion,
            state =
                RunnerReadinessState.Booting(
                    leaseId = leaseId,
                    attempt = workspace.runnerBootAttempt,
                    startedAt = workspace.runnerBootStartedAt ?: now,
                ),
            checkedAt = now,
        )

    /**
     * Periodic sweep that releases boot leases stuck without completing
     * normally.  Increments runner_boot_attempt so recoveries are visible.
     */
    @Scheduled(fixedDelayString = "\${agent-runtime.runner-boot-lease-sweep-period-ms:120000}")
    fun reconcileStaleBootLeases() {
        val now = clock.instant()
        val olderThan = now.minus(Duration.ofSeconds(bootLeaseTimeoutSeconds))
        val stale =
            workspaces
                .findAllByStatusNot(WorkspaceStatus.DESTROYED)
                .filter { it.isStaleBootLease(olderThan) }
        if (stale.isEmpty()) return

        val released = stale.count { reclaim(it, olderThan, now) }
        if (released > 0) log.info("runner-boot-lease reconciler released {} workspace(s)", released)
    }

    private fun provision(
        workspace: Workspace,
        target: RunnerSetupTarget,
        leaseId: UUID,
    ): BootOutcome {
        val handle =
            runCatching {
                orchestrator.scaleDown(workspace)
                orchestrator.provision(workspace, target.spec, workspace.runnerSetupGeneration)
            }.getOrElse { ex ->
                return provisionFailed(workspace, target, leaseId, ex)
            }

        val booted = workspace.withPodInfo(handle.podName, handle.pvcName, handle.gatewayEndpoint)
        val saved = workspaces.save(booted)
        log.info("provisioned runner for workspace {} as pod {}", workspace.id.value, handle.podName)

        val identity = target.spec.identity(saved.runnerSetupGeneration)
        val ready = runCatching { orchestrator.isReady(saved, identity) }.getOrDefault(false)

        return provisionOutcome(saved, target, leaseId, handle, ready)
    }

    private fun provisionFailed(
        workspace: Workspace,
        target: RunnerSetupTarget,
        leaseId: UUID,
        ex: Throwable,
    ): BootOutcome.Conflict {
        runCatching { workspaces.failBootLease(workspace.id, leaseId) }
        publish(unavailableSnapshot(workspace, target, RunnerUnavailableReason.PROVISION_FAILED, clock.instant()))
        log.warn("runner provision failed for workspace {}: {}", workspace.id.value, ex.message)
        return BootOutcome.Conflict(RunnerUnavailableReason.PROVISION_FAILED)
    }

    private fun provisionOutcome(
        saved: Workspace,
        target: RunnerSetupTarget,
        leaseId: UUID,
        handle: AgentRunnerOrchestrator.RunnerHandle,
        ready: Boolean,
    ): BootOutcome =
        if (ready) {
            workspaces.completeBootLease(saved.id, leaseId)
            publish(readySnapshot(saved, target, clock.instant()))
            BootOutcome.Ready(
                workspace = saved,
                provisioning =
                    BootProvisioningOutcome.Provisioned(
                        handle.podName,
                        handle.pvcName,
                        handle.gatewayEndpoint,
                    ),
            )
        } else {
            workspaces.failBootLease(saved.id, leaseId)
            publish(
                unavailableSnapshot(saved, target, RunnerUnavailableReason.NOT_READY_AFTER_PROVISION, clock.instant()),
            )
            BootOutcome.Conflict(RunnerUnavailableReason.NOT_READY_AFTER_PROVISION)
        }

    private fun reclaim(
        workspace: Workspace,
        olderThan: Instant,
        now: Instant,
    ): Boolean {
        val released =
            runCatching { workspaces.releaseStaleBootLease(workspace.id, olderThan, now) }
                .getOrElse { ex ->
                    log.warn("boot-lease reclaim of {} failed: {}", workspace.id.value, ex.message)
                    return false
                }
        if (released) {
            log.warn(
                "released stale boot lease for workspace {} (held since {})",
                workspace.id.value,
                workspace.runnerBootStartedAt,
            )
        }
        return released
    }

    private fun readySnapshot(
        workspace: Workspace,
        target: RunnerSetupTarget,
        checkedAt: Instant,
    ): RunnerReadinessSnapshot =
        RunnerReadinessSnapshot(
            workspaceId = workspace.id,
            setupId = target.entry.definition.id,
            setupVersion = target.entry.definition.version,
            state = RunnerReadinessState.Ready,
            checkedAt = checkedAt,
        )

    private fun unavailableSnapshot(
        workspace: Workspace,
        target: RunnerSetupTarget,
        reason: RunnerUnavailableReason,
        checkedAt: Instant,
    ): RunnerReadinessSnapshot =
        RunnerReadinessSnapshot(
            workspaceId = workspace.id,
            setupId = target.entry.definition.id,
            setupVersion = target.entry.definition.version,
            state = RunnerReadinessState.Unavailable(reason, checkedAt),
            checkedAt = checkedAt,
        )

    private fun publish(snapshot: RunnerReadinessSnapshot) =
        runCatching { readinessPublisher.publish(snapshot) }
            .onFailure { log.warn("readiness publish failed for {}: {}", snapshot.workspaceId.value, it.message) }

    private fun Workspace.isStaleBootLease(olderThan: Instant): Boolean {
        if (runnerBootLeaseId == null) return false
        val updatedAt = runnerBootUpdatedAt ?: return false
        return updatedAt.isBefore(olderThan)
    }
}

private fun currentReadinessState(
    workspace: Workspace,
    ready: Boolean,
): RunnerReadinessState =
    if (ready) {
        RunnerReadinessState.Ready
    } else {
        RunnerReadinessState.Unavailable(
            reason = RunnerUnavailableReason.NOT_READY_AFTER_PROVISION,
            since = workspace.runnerBootUpdatedAt,
        )
    }

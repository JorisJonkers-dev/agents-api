package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import java.time.Instant
import java.util.UUID

interface WorkspaceRepository {
    data class RunnerSetupOperationRequest(
        val id: WorkspaceId,
        val expectedGeneration: Long,
        val setupId: AgentSetupId,
        val setupVersion: AgentSetupVersion,
        val operation: RunnerSetupOperation = RunnerSetupOperation.RESTARTING,
        val now: Instant = Instant.now(),
    )

    fun save(workspace: Workspace): Workspace

    fun findById(id: WorkspaceId): Workspace?

    fun findAllByStatusNot(status: WorkspaceStatus): List<Workspace>

    fun beginRunnerSetupOperation(request: RunnerSetupOperationRequest): Boolean

    fun completeRunnerSetupOperation(
        id: WorkspaceId,
        expectedGeneration: Long,
        now: Instant = Instant.now(),
    ): Boolean

    fun failRunnerSetupOperation(
        id: WorkspaceId,
        expectedGeneration: Long,
        now: Instant = Instant.now(),
    ): Boolean

    /**
     * Release a runner-setup lease that has been stuck in a non-IDLE
     * state since before [olderThan], resetting it to IDLE and dropping
     * the pending setup so the workspace can bind a new session again.
     *
     * The age predicate (`runner_setup_operation_updated_at < olderThan`)
     * is the safety guard: a live `start`/`restart` holds the lease only
     * for the few seconds of provisioning, so a generous [olderThan]
     * never preempts an in-flight operation — it only reclaims leases
     * orphaned by a crash mid-provision (RESTARTING) or left terminal by
     * a failed provision (FAILED). Returns true when a row was reset.
     */
    fun releaseStaleRunnerSetupOperation(
        id: WorkspaceId,
        olderThan: Instant,
        now: Instant = Instant.now(),
    ): Boolean

    /**
     * Acquire the boot lease atomically — sets runner_boot_lease_id = [leaseId]
     * only when no lease is currently held. Returns true when the lease was taken.
     * Callers MUST check the return value and abort any K8s operation when false.
     */
    fun acquireBootLease(
        id: WorkspaceId,
        leaseId: UUID,
        now: Instant = Instant.now(),
    ): Boolean

    /**
     * Release the boot lease on successful completion. Only succeeds when the
     * stored lease id matches [leaseId]; does not increment the attempt counter.
     */
    fun completeBootLease(
        id: WorkspaceId,
        leaseId: UUID,
        now: Instant = Instant.now(),
    ): Boolean

    /**
     * Release the boot lease after a failed boot. Only succeeds when the stored
     * lease id matches [leaseId]; increments runner_boot_attempt so repeated
     * failures are observable.
     */
    fun failBootLease(
        id: WorkspaceId,
        leaseId: UUID,
        now: Instant = Instant.now(),
    ): Boolean

    /**
     * Release a boot lease that has been held since before [olderThan] without
     * completing normally (crash mid-boot or pod stuck). Increments the attempt
     * counter so the recovery is visible. Returns true when a row was reset.
     */
    fun releaseStaleBootLease(
        id: WorkspaceId,
        olderThan: Instant,
        now: Instant = Instant.now(),
    ): Boolean

    fun delete(id: WorkspaceId)
}

package com.jorisjonkers.personalstack.agents.application.workspacerunner

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.RunnerState
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class WorkspaceRunnerLifecycleServiceTest {
    private val now = Instant.parse("2026-06-17T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val workspaces = mockk<WorkspaceRepository>()
    private val orchestrator = mockk<AgentRunnerOrchestrator>()
    private val targetResolver = mockk<RunnerSetupTargetResolver>()
    private val readinessPublisher = mockk<RunnerReadinessPublisher>(relaxed = true)

    private val service =
        WorkspaceRunnerLifecycleService(
            workspaces = workspaces,
            orchestrator = orchestrator,
            targetResolver = targetResolver,
            readinessPublisher = readinessPublisher,
            clock = clock,
        )

    private val workspaceId = WorkspaceId.random()
    private val setupId = AgentSetupId("default")
    private val setupVersion = AgentSetupVersion(1L)

    private val workspace =
        Workspace(
            id = workspaceId,
            name = "demo",
            repoUrl = "git@github.com:o/r.git",
            branch = "main",
            podName = "agent-runner-abc",
            pvcName = "workspace-abc",
            gatewayEndpoint = "http://x:8090",
            status = WorkspaceStatus.READY,
            createdAt = now.minusSeconds(3_600),
            updatedAt = now.minusSeconds(60),
            currentRunnerSetupId = setupId,
            currentRunnerSetupVersion = setupVersion,
            runnerSetupGeneration = 3,
        )

    private val identity = RunnerState.Identity(setupId, setupVersion, "abc123", 3L)
    private val target = RunnerSetupTarget(entry = mockCatalogEntry(), spec = mockSpec())

    @Test
    fun `returns Ready-AlreadyReady when runner is already ready`() {
        every { workspaces.findById(workspaceId) } returns workspace
        every { targetResolver.resolve(workspace, WorkspaceAgentKind.CLAUDE, null, null) } returns target
        every { orchestrator.isReady(workspace, identity) } returns true

        val result = service.boot(workspaceId, WorkspaceAgentKind.CLAUDE)

        assertThat(result).isInstanceOf(WorkspaceRunnerLifecycleService.BootOutcome.Ready::class.java)
        val ready = result as WorkspaceRunnerLifecycleService.BootOutcome.Ready
        assertThat(ready.provisioning)
            .isInstanceOf(WorkspaceRunnerLifecycleService.BootProvisioningOutcome.AlreadyReady::class.java)
        verify { readinessPublisher.publish(match { it.state == RunnerReadinessState.Ready }) }
    }

    @Test
    fun `returns Conflict when setup operation is in progress`() {
        val busyWorkspace = workspace.copy(runnerSetupOperation = RunnerSetupOperation.RESTARTING)
        every { workspaces.findById(workspaceId) } returns busyWorkspace
        every { targetResolver.resolve(busyWorkspace, WorkspaceAgentKind.CLAUDE, null, null) } returns target
        every { orchestrator.isReady(busyWorkspace, identity) } returns false

        val result = service.boot(workspaceId, WorkspaceAgentKind.CLAUDE)

        assertThat(result).isEqualTo(
            WorkspaceRunnerLifecycleService.BootOutcome.Conflict(RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS),
        )
    }

    @Test
    fun `returns Conflict when boot lease is already held in persisted state`() {
        val leasedWorkspace = workspace.copy(runnerBootLeaseId = UUID.randomUUID())
        every { workspaces.findById(workspaceId) } returns leasedWorkspace
        every { targetResolver.resolve(leasedWorkspace, WorkspaceAgentKind.CLAUDE, null, null) } returns target
        every { orchestrator.isReady(leasedWorkspace, identity) } returns false

        val result = service.boot(workspaceId, WorkspaceAgentKind.CLAUDE)

        assertThat(result).isEqualTo(
            WorkspaceRunnerLifecycleService.BootOutcome.Conflict(RunnerUnavailableReason.BOOT_LEASE_HELD),
        )
    }

    @Test
    fun `returns Conflict when CAS boot-lease acquisition loses a concurrent race`() {
        every { workspaces.findById(workspaceId) } returns workspace
        every { targetResolver.resolve(workspace, WorkspaceAgentKind.CLAUDE, null, null) } returns target
        every { orchestrator.isReady(workspace, identity) } returns false
        every { workspaces.acquireBootLease(workspaceId, any(), any()) } returns false

        val result = service.boot(workspaceId, WorkspaceAgentKind.CLAUDE)

        assertThat(result).isEqualTo(
            WorkspaceRunnerLifecycleService.BootOutcome.Conflict(RunnerUnavailableReason.BOOT_LEASE_HELD),
        )
        verify(exactly = 0) { orchestrator.scaleDown(any()) }
    }

    @Test
    fun `acquires lease before K8s operations and completes it on successful provision`() {
        val handle = AgentRunnerOrchestrator.RunnerHandle("pod-x", "pvc-x", "http://pod-x:8090")
        val provisioned = workspace.copy(podName = "pod-x", pvcName = "pvc-x", gatewayEndpoint = "http://pod-x:8090")

        every { workspaces.findById(workspaceId) } returns workspace
        every { targetResolver.resolve(workspace, WorkspaceAgentKind.CLAUDE, null, null) } returns target
        every { orchestrator.isReady(workspace, identity) } returns false
        every { workspaces.acquireBootLease(workspaceId, any(), any()) } returns true
        every { orchestrator.scaleDown(workspace) } returns Unit
        every { orchestrator.provision(workspace, target.spec, workspace.runnerSetupGeneration) } returns handle
        every { workspaces.save(any()) } returns provisioned
        every { orchestrator.isReady(provisioned, identity) } returns true
        every { workspaces.completeBootLease(provisioned.id, any(), any()) } returns true

        val result = service.boot(workspaceId, WorkspaceAgentKind.CLAUDE)

        assertThat(result).isInstanceOf(WorkspaceRunnerLifecycleService.BootOutcome.Ready::class.java)
        val ready = result as WorkspaceRunnerLifecycleService.BootOutcome.Ready
        val provisioning = ready.provisioning as WorkspaceRunnerLifecycleService.BootProvisioningOutcome.Provisioned
        assertThat(provisioning.podName).isEqualTo("pod-x")
        verify { workspaces.acquireBootLease(workspaceId, any(), any()) }
        verify { orchestrator.scaleDown(workspace) }
        verify { workspaces.completeBootLease(provisioned.id, any(), any()) }
        verify(exactly = 0) { workspaces.failBootLease(any(), any(), any()) }
    }

    @Test
    fun `fails boot lease and returns Conflict when provision throws`() {
        every { workspaces.findById(workspaceId) } returns workspace
        every { targetResolver.resolve(workspace, WorkspaceAgentKind.CLAUDE, null, null) } returns target
        every { orchestrator.isReady(workspace, identity) } returns false
        every { workspaces.acquireBootLease(workspaceId, any(), any()) } returns true
        every { orchestrator.scaleDown(workspace) } throws RuntimeException("k8s unreachable")
        every { workspaces.failBootLease(workspaceId, any(), any()) } returns true

        val result = service.boot(workspaceId, WorkspaceAgentKind.CLAUDE)

        assertThat(result).isEqualTo(
            WorkspaceRunnerLifecycleService.BootOutcome.Conflict(RunnerUnavailableReason.PROVISION_FAILED),
        )
        verify { workspaces.failBootLease(workspaceId, any(), any()) }
        verify(exactly = 0) { workspaces.completeBootLease(any(), any(), any()) }
    }

    @Test
    fun `fails boot lease and returns Conflict when orchestrator not ready after provision`() {
        val handle = AgentRunnerOrchestrator.RunnerHandle("pod-y", "pvc-y", "http://pod-y:8090")
        val provisioned = workspace.copy(podName = "pod-y", pvcName = "pvc-y", gatewayEndpoint = "http://pod-y:8090")

        every { workspaces.findById(workspaceId) } returns workspace
        every { targetResolver.resolve(workspace, WorkspaceAgentKind.CLAUDE, null, null) } returns target
        every { orchestrator.isReady(workspace, identity) } returns false
        every { workspaces.acquireBootLease(workspaceId, any(), any()) } returns true
        every { orchestrator.scaleDown(workspace) } returns Unit
        every { orchestrator.provision(workspace, target.spec, workspace.runnerSetupGeneration) } returns handle
        every { workspaces.save(any()) } returns provisioned
        every { orchestrator.isReady(provisioned, identity) } returns false
        every { workspaces.failBootLease(provisioned.id, any(), any()) } returns true

        val result = service.boot(workspaceId, WorkspaceAgentKind.CLAUDE)

        assertThat(result).isEqualTo(
            WorkspaceRunnerLifecycleService.BootOutcome.Conflict(RunnerUnavailableReason.NOT_READY_AFTER_PROVISION),
        )
        verify { workspaces.failBootLease(provisioned.id, any(), any()) }
        verify(exactly = 0) { workspaces.completeBootLease(any(), any(), any()) }
    }

    @Test
    fun `returns Conflict when workspace not found`() {
        every { workspaces.findById(workspaceId) } returns null

        val result = service.boot(workspaceId, WorkspaceAgentKind.CLAUDE)

        assertThat(result).isEqualTo(
            WorkspaceRunnerLifecycleService.BootOutcome.Conflict(RunnerUnavailableReason.WORKSPACE_NOT_FOUND),
        )
    }

    @Test
    fun `readinessSnapshot returns Booting state when lease is held`() {
        val leaseId = UUID.randomUUID()
        val booting =
            workspace.copy(
                runnerBootLeaseId = leaseId,
                runnerBootAttempt = 2,
                runnerBootStartedAt = now.minusSeconds(30),
            )

        val snapshot = service.readinessSnapshot(booting)

        assertThat(snapshot.state).isInstanceOf(RunnerReadinessState.Booting::class.java)
        val state = snapshot.state as RunnerReadinessState.Booting
        assertThat(state.leaseId).isEqualTo(leaseId)
        assertThat(state.attempt).isEqualTo(2)
        verify(exactly = 0) { orchestrator.isReady(any()) }
    }

    @Test
    fun `readinessSnapshot returns Ready when no lease and orchestrator is ready`() {
        every { orchestrator.isReady(workspace) } returns true

        val snapshot = service.readinessSnapshot(workspace)

        assertThat(snapshot.state).isEqualTo(RunnerReadinessState.Ready)
    }

    @Test
    fun `readinessSnapshot returns Unavailable when no lease and orchestrator is not ready`() {
        every { orchestrator.isReady(workspace) } returns false

        val snapshot = service.readinessSnapshot(workspace)

        assertThat(snapshot.state).isInstanceOf(RunnerReadinessState.Unavailable::class.java)
        val state = snapshot.state as RunnerReadinessState.Unavailable
        assertThat(state.reason).isEqualTo(RunnerUnavailableReason.NOT_READY_AFTER_PROVISION)
    }

    @Test
    fun `reconcileStaleBootLeases releases a stale lease`() {
        val leaseId = UUID.randomUUID()
        val staleAt = now.minusSeconds(3_600)
        val stale = workspace.copy(runnerBootLeaseId = leaseId, runnerBootUpdatedAt = staleAt)
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(stale)
        every { workspaces.releaseStaleBootLease(stale.id, any(), now) } returns true

        service.reconcileStaleBootLeases()

        verify { workspaces.releaseStaleBootLease(stale.id, any(), now) }
    }

    @Test
    fun `reconcileStaleBootLeases leaves a fresh lease untouched`() {
        val freshAt = now.minusSeconds(10)
        val fresh = workspace.copy(runnerBootLeaseId = UUID.randomUUID(), runnerBootUpdatedAt = freshAt)
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(fresh)

        service.reconcileStaleBootLeases()

        verify(exactly = 0) { workspaces.releaseStaleBootLease(any(), any(), any()) }
    }

    @Test
    fun `reconcileStaleBootLeases skips workspaces with no boot lease`() {
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(workspace)

        service.reconcileStaleBootLeases()

        verify(exactly = 0) { workspaces.releaseStaleBootLease(any(), any(), any()) }
    }

    // ---- helpers ----

    private fun mockCatalogEntry(): AgentSetupCatalogEntry {
        val def = mockk<AgentSetupDefinition>()
        every { def.id } returns setupId
        every { def.version } returns setupVersion
        val avail = mockk<AgentSetupAvailability>()
        return AgentSetupCatalogEntry(def, avail)
    }

    private fun mockSpec(): RunnerSetupProvisioningSpec =
        mockk<RunnerSetupProvisioningSpec>().also {
            every { it.identity(3L) } returns identity
        }
}

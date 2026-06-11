package com.jorisjonkers.personalstack.agents.application.maintenance

import com.jorisjonkers.personalstack.agents.application.idle.WorkspaceActivityTracker
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RunnerMaintenanceServiceTest {
    private val now = Instant.parse("2026-05-19T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val workspaces = mockk<WorkspaceRepository>()
    private val orchestrator = mockk<AgentRunnerOrchestrator>(relaxed = true)
    private val tracker = WorkspaceActivityTracker(clock)
    private val service =
        RunnerMaintenanceService(
            workspaces = workspaces,
            orchestrator = orchestrator,
            tracker = tracker,
            clock = clock,
        )

    @Test
    fun `gracefulScaleDownAll scales down READY workspaces and returns their ids`() {
        val ws = workspace(WorkspaceStatus.READY)
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        val saved = slot<Workspace>()
        every { workspaces.save(capture(saved)) } answers { saved.captured }

        val result = service.gracefulScaleDownAll()

        verify { orchestrator.scaleDown(ws) }
        assertThat(saved.captured.status).isEqualTo(WorkspaceStatus.IDLE)
        assertThat(saved.captured.podName).isNull()
        assertThat(saved.captured.gatewayEndpoint).isNull()
        assertThat(result.cycled).isEqualTo(1)
        assertThat(result.workspaceIds).containsExactly(ws.id.value.toString())
    }

    @Test
    fun `gracefulScaleDownAll also cycles STARTING and FAILED workspaces`() {
        val starting = workspace(WorkspaceStatus.STARTING)
        val failed = workspace(WorkspaceStatus.FAILED)
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(starting, failed)
        every { workspaces.save(any()) } answers { firstArg() }

        val result = service.gracefulScaleDownAll()

        verify { orchestrator.scaleDown(starting) }
        verify { orchestrator.scaleDown(failed) }
        assertThat(result.cycled).isEqualTo(2)
    }

    @Test
    fun `gracefulScaleDownAll leaves IDLE workspaces untouched`() {
        val idle = workspace(WorkspaceStatus.IDLE)
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(idle)

        val result = service.gracefulScaleDownAll()

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
        verify(exactly = 0) { workspaces.save(any()) }
        assertThat(result.cycled).isEqualTo(0)
    }

    @Test
    fun `gracefulScaleDownAll skips workspace when orchestrator scaleDown throws`() {
        val ws = workspace(WorkspaceStatus.READY)
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        every { orchestrator.scaleDown(ws) } throws RuntimeException("k8s unreachable")

        val result = service.gracefulScaleDownAll()

        verify(exactly = 0) { workspaces.save(any()) }
        assertThat(result.cycled).isEqualTo(0)
    }

    @Test
    fun `gracefulScaleDownAll forgets workspace from activity tracker`() {
        val ws = workspace(WorkspaceStatus.READY)
        tracker.touch(ws.id)
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        every { workspaces.save(any()) } answers { firstArg() }

        service.gracefulScaleDownAll()

        assertThat(tracker.lastSeen(ws.id)).isNull()
    }

    private fun workspace(status: WorkspaceStatus) =
        Workspace(
            id = WorkspaceId.random(),
            name = "test",
            repoUrl = null,
            branch = null,
            podName = "agent-runner-deadbeef",
            pvcName = "workspace-deadbeef",
            gatewayEndpoint = "http://x:8090",
            status = status,
            createdAt = now.minusSeconds(3600),
            updatedAt = now.minusSeconds(60),
        )
}

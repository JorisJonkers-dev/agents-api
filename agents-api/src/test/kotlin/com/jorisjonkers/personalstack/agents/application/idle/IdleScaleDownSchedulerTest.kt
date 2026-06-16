package com.jorisjonkers.personalstack.agents.application.idle

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
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

class IdleScaleDownSchedulerTest {
    private class RecordingTelemetry : AgentsApiTelemetry {
        val operations = mutableListOf<OperationTelemetry>()

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }
    }

    private class FixedClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = now
    }

    private val now = Instant.parse("2026-05-19T12:00:00Z")
    private val clock = FixedClock(now)
    private val workspaces = mockk<WorkspaceRepository>()
    private val agentSessions = mockk<WorkspaceAgentSessionRepository>()
    private val orchestrator = mockk<AgentRunnerOrchestrator>(relaxed = true)
    private val tracker = WorkspaceActivityTracker(clock)
    private val connected = ConnectedClientTracker()
    private val sessionStatus = mockk<SessionStatusPublisher>(relaxed = true)
    private val telemetry = RecordingTelemetry()
    private val scheduler =
        IdleScaleDownScheduler(
            workspaces = workspaces,
            agentSessions = agentSessions,
            orchestrator = orchestrator,
            tracker = tracker,
            connected = connected,
            sessionStatus = sessionStatus,
            clock = clock,
            telemetry = telemetry,
            idleAfterSeconds = 1_800,
            agentIdleAfterSeconds = 14_400,
        )

    private fun noRunningSessions(ws: Workspace) {
        every { agentSessions.findAllByWorkspaceId(ws.id) } returns emptyList()
    }

    @Test
    fun `sweep scales down a READY workspace whose last activity is older than the threshold`() {
        val ws = workspace(updatedAt = now.minusSeconds(7_200))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        noRunningSessions(ws)
        val saved = slot<Workspace>()
        every { workspaces.save(capture(saved)) } answers { saved.captured }

        scheduler.sweep()

        verify { orchestrator.scaleDown(ws) }
        verify(exactly = 0) { orchestrator.destroy(any()) }
        assertThat(saved.captured.status).isEqualTo(WorkspaceStatus.IDLE)
        assertThat(saved.captured.podName).isNull()
        assertThat(saved.captured.gatewayEndpoint).isNull()
        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.SUCCESS)
        assertBoundedTelemetryLabels(ws.id.value.toString())
    }

    @Test
    fun `sweep leaves recently active workspaces alone`() {
        val ws = workspace(updatedAt = now.minusSeconds(60))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        noRunningSessions(ws)
        tracker.touch(ws.id)

        scheduler.sweep()

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
        verify(exactly = 0) { orchestrator.destroy(any()) }
        verify(exactly = 0) { workspaces.save(any()) }
        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.SKIPPED)
        assertBoundedTelemetryLabels(ws.id.value.toString())
    }

    @Test
    fun `sweep skips workspaces not in READY status`() {
        val ws = workspace(updatedAt = now.minusSeconds(7_200), status = WorkspaceStatus.PENDING)
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)

        scheduler.sweep()

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
        verify(exactly = 0) { orchestrator.destroy(any()) }
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.INVALID_REQUEST)
    }

    @Test
    fun `sweep skips workspace during runner setup transition`() {
        val ws =
            workspace(updatedAt = now.minusSeconds(7_200))
                .copy(
                    pendingRunnerSetupId = AgentSetupId("gpu"),
                    pendingRunnerSetupVersion = AgentSetupVersion(2),
                )
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)

        scheduler.sweep()

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.SKIPPED)
        assertBoundedTelemetryLabels(ws.id.value.toString(), "gpu")
    }

    @Test
    fun `sweep skips workspace with a session pending setup promotion`() {
        val ws = workspace(updatedAt = now.minusSeconds(14_401))
        val session =
            runningSession(ws.id)
                .copy(
                    pendingSetupId = AgentSetupId("gpu"),
                    pendingSetupVersion = AgentSetupVersion(2),
                )
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        every { agentSessions.findAllByWorkspaceId(ws.id) } returns listOf(session)

        scheduler.sweep()

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
    }

    @Test
    fun `tracker timestamp wins over workspace updatedAt when fresher`() {
        val ws = workspace(updatedAt = now.minusSeconds(7_200))
        tracker.touch(ws.id) // fresh-as-now
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        noRunningSessions(ws)

        scheduler.sweep()

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
        verify(exactly = 0) { orchestrator.destroy(any()) }
    }

    @Test
    fun `sweep skips a workspace with a connected browser client regardless of idle time`() {
        val ws = workspace(updatedAt = now.minusSeconds(7_200))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        connected.attach(ws.id)

        scheduler.sweep()

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
    }

    @Test
    fun `sweep recycles a disconnected runner on a stale image even when recently active`() {
        val ws = workspace(updatedAt = now.minusSeconds(60))
        tracker.touch(ws.id) // fresh — the idle timer alone would not scale this down
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        noRunningSessions(ws)
        every { orchestrator.isRunnerImageStale(ws) } returns true
        every { workspaces.save(any()) } answers { firstArg() }

        scheduler.sweep()

        verify { orchestrator.scaleDown(ws) }
    }

    @Test
    fun `sweep does not recycle a stale-image runner while a client is connected`() {
        val ws = workspace(updatedAt = now.minusSeconds(60))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        connected.attach(ws.id)
        every { orchestrator.isRunnerImageStale(ws) } returns true

        scheduler.sweep()

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
    }

    @Test
    fun `sweep skips a workspace with RUNNING sessions inside the agent idle threshold`() {
        val ws = workspace(updatedAt = now.minusSeconds(7_200))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        every { agentSessions.findAllByWorkspaceId(ws.id) } returns listOf(runningSession(ws.id))

        scheduler.sweep()

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
    }

    @Test
    fun `sweep scales down a workspace with RUNNING sessions once agent idle threshold expires`() {
        val ws = workspace(updatedAt = now.minusSeconds(14_401))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        val session = runningSession(ws.id)
        every { agentSessions.findAllByWorkspaceId(ws.id) } returns listOf(session)
        every { workspaces.save(any()) } answers { firstArg() }
        every {
            agentSessions.clearGatewayBindingIfGeneration(
                id = session.id,
                expectedGeneration = session.generation,
                now = now,
            )
        } returns true

        scheduler.sweep()

        verify { orchestrator.scaleDown(ws) }
    }

    @Test
    fun `sweep records bounded failure when scale-down throws`() {
        val ws = workspace(updatedAt = now.minusSeconds(7_200))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        noRunningSessions(ws)
        every { orchestrator.scaleDown(ws) } throws RuntimeException("scale failed for ${ws.id}")

        scheduler.sweep()

        verify(exactly = 0) { workspaces.save(any()) }
        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.FAILURE)
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
        assertBoundedTelemetryLabels("scale failed", ws.id.value.toString())
    }

    private fun assertBoundedTelemetryLabels(vararg forbidden: String) {
        val labels =
            telemetry.operations.flatMap {
                listOf(it.operation.label, it.mode.label, it.outcome.label, it.reason.label)
            }
        forbidden
            .filter { it.isNotBlank() }
            .forEach { raw ->
                assertThat(labels).allSatisfy { label ->
                    assertThat(label).doesNotContain(raw)
                }
            }
    }

    @Test
    fun `sweep clears current gateway bindings without deleting durable sessions`() {
        val ws = workspace(updatedAt = now.minusSeconds(14_401))
        val session =
            runningSession(ws.id)
                .copy(
                    gatewayAgentId = "gateway-agent-1",
                    epoch = 4,
                    generation = 9,
                    gatewayBoundAt = now.minusSeconds(300),
                )
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns listOf(ws)
        every { agentSessions.findAllByWorkspaceId(ws.id) } returns listOf(session)
        every { workspaces.save(any()) } answers { firstArg() }
        every {
            agentSessions.clearGatewayBindingIfGeneration(
                id = session.id,
                expectedGeneration = 9,
                now = now,
            )
        } returns true

        scheduler.sweep()

        verify {
            agentSessions.clearGatewayBindingIfGeneration(
                id = session.id,
                expectedGeneration = 9,
                now = now,
            )
        }
        verify {
            sessionStatus.publishStatus(
                match { it.id == session.id && it.gatewayAgentId == null },
                idle = true,
            )
        }
        verify(exactly = 0) { agentSessions.delete(any()) }
    }

    private fun runningSession(workspaceId: WorkspaceId) =
        WorkspaceAgentSession(
            id = WorkspaceAgentSessionId.random(),
            workspaceId = workspaceId,
            kind = WorkspaceAgentKind.CLAUDE,
            gatewayAgentId = "gateway-agent",
            status = WorkspaceAgentSessionStatus.RUNNING,
            createdAt = now.minusSeconds(7_200),
            updatedAt = now.minusSeconds(7_200),
            epoch = 2,
            generation = 5,
            gatewayBoundAt = now.minusSeconds(3_600),
        )

    private fun workspace(
        updatedAt: Instant,
        status: WorkspaceStatus = WorkspaceStatus.READY,
    ) = Workspace(
        id = WorkspaceId.random(),
        name = "demo",
        repoUrl = null,
        branch = null,
        podName = "agent-runner-deadbeef",
        pvcName = "workspace-deadbeef",
        gatewayEndpoint = "http://x:8090",
        status = status,
        createdAt = updatedAt.minusSeconds(1),
        updatedAt = updatedAt,
    )
}

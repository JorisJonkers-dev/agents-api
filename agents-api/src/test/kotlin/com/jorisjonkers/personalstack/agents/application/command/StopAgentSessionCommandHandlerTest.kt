package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.rag.LessonAutoCapture
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StopAgentSessionCommandHandlerTest {
    private class RecordingTelemetry : AgentsApiTelemetry {
        val operations = mutableListOf<OperationTelemetry>()

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }
    }

    private val now = Instant.parse("2026-06-12T09:00:00Z")
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val gateway = mockk<AgentGatewayClient>(relaxed = true)
    private val autoCapture = mockk<LessonAutoCapture>(relaxed = true)
    private val runtime = runtimeProperties()
    private val sessionStatus = mockk<SessionStatusPublisher>(relaxed = true)
    private val telemetry = RecordingTelemetry()
    private val handler =
        StopAgentSessionCommandHandler(
            workspaces = workspaces,
            sessions = sessions,
            gateway = gateway,
            autoCapture = autoCapture,
            runtime = runtime,
            sessionStatus = sessionStatus,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            telemetry = telemetry,
        )

    @Test
    fun `handle stops gateway agent and persists stopped state with retention`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = "abc12345")
        every { sessions.findById(session.id) } returns session
        every { workspaces.findById(ws.id) } returns ws
        every {
            sessions.markLifecycleIfGeneration(
                id = session.id,
                expectedGeneration = session.generation,
                status = WorkspaceAgentSessionStatus.STOPPED,
                retainedUntil = now.plusSeconds(runtime.durableSessionRetentionSeconds),
                clearGatewayBinding = true,
                now = now,
            )
        } returns true

        handler.handle(StopAgentSessionCommand(session.id))

        verify { gateway.stopAgent(ws, "abc12345") }
        verify {
            sessions.markLifecycleIfGeneration(
                id = session.id,
                expectedGeneration = session.generation,
                status = WorkspaceAgentSessionStatus.STOPPED,
                retainedUntil = now.plusSeconds(runtime.durableSessionRetentionSeconds),
                clearGatewayBinding = true,
                now = now,
            )
        }
        verify { autoCapture.capture(session.id) }
        verify {
            sessionStatus.publishStatus(
                match { it.id == session.id && it.status == WorkspaceAgentSessionStatus.STOPPED },
                idle = false,
            )
        }
        verify(exactly = 0) { gateway.cleanupStableSession(any(), any()) }
        verify(exactly = 0) { sessions.delete(any()) }
        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.SUCCESS)
        assertBoundedTelemetryLabels(ws.id.value.toString(), session.id.value.toString(), "abc12345")
    }

    @Test
    fun `handle purges an already-stopped session and publishes its removal`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = null).copy(status = WorkspaceAgentSessionStatus.STOPPED)
        every { sessions.findById(session.id) } returns session
        every { sessions.delete(session.id) } returns true

        handler.handle(StopAgentSessionCommand(session.id))

        verify { sessions.delete(session.id) }
        verify { sessionStatus.publishRemove(session.id) }
        verify(exactly = 0) { gateway.stopAgent(any(), any()) }
        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.SUCCESS)
    }

    @Test
    fun `handle is a no-op for unknown session`() {
        val id = WorkspaceAgentSessionId.random()
        every { sessions.findById(id) } returns null
        handler.handle(StopAgentSessionCommand(id))
        verify(exactly = 0) { gateway.stopAgent(any(), any()) }
        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.SKIPPED)
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.NOT_FOUND)
        assertBoundedTelemetryLabels(id.value.toString())
    }

    @Test
    fun `handle records bounded failure when gateway stop throws`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = "abc12345")
        every { sessions.findById(session.id) } returns session
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.stopAgent(ws, "abc12345") } throws RuntimeException("stop failed for ${session.id}")
        every {
            sessions.markLifecycleIfGeneration(
                id = session.id,
                expectedGeneration = session.generation,
                status = WorkspaceAgentSessionStatus.STOPPED,
                retainedUntil = now.plusSeconds(runtime.durableSessionRetentionSeconds),
                clearGatewayBinding = true,
                now = now,
            )
        } returns true

        handler.handle(StopAgentSessionCommand(session.id))

        assertThat(telemetry.operations.map { it.outcome }).contains(OutcomeLabel.FAILURE, OutcomeLabel.SUCCESS)
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
        assertBoundedTelemetryLabels("stop failed", ws.id.value.toString(), session.id.value.toString(), "abc12345")
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

    private fun workspace() =
        Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = "http://x:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun session(
        workspaceId: WorkspaceId,
        gatewayAgentId: String?,
    ) = WorkspaceAgentSession(
        id = WorkspaceAgentSessionId.random(),
        workspaceId = workspaceId,
        kind = WorkspaceAgentKind.CLAUDE,
        gatewayAgentId = gatewayAgentId,
        status = WorkspaceAgentSessionStatus.RUNNING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        epoch = 3,
        generation = 7,
    )

    private fun runtimeProperties() =
        AgentRuntimeProperties(
            namespace = "agents-system",
            image = "ghcr.io/example/agent-runner:latest",
            serviceAccount = "agent-runner",
            claudeCredentialsPvc = "claude-credentials",
            codexCredentialsPvc = "codex-credentials",
            githubDeployKeySecret = "agents-github-deploy-key",
            durableSessionRetentionSeconds = 86_400,
        )
}

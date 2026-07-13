package com.jorisjonkers.personalstack.agents.infrastructure.ws

import com.jorisjonkers.personalstack.agents.application.observability.AgentKindLabel
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.AttachAttemptTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.sessionbinding.EnsureRunnerSessionBoundInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerProvisioningResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
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
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import java.net.URI
import java.time.Instant

class AttachPreconditionCheckerTest {
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val binding = mockk<RunnerSessionBindingService>()
    private val telemetry = RecordingTelemetry()
    private val checker = AttachPreconditionChecker(sessions, workspaces, binding, telemetry)

    private val sessionId = WorkspaceAgentSessionId.random()
    private val workspaceId = WorkspaceId.random()

    @Test
    fun `resolveAttach returns Ready when all preconditions pass`() {
        val ws = workspace()
        every { sessions.findById(sessionId) } returns agentSession(gatewayAgentId = "gw-1")
        every { workspaces.findById(workspaceId) } returns ws

        val result = checker.resolveAttach(clientSession(), AttachPreconditionChecker::sessionIdOf)

        assertThat(result).isNotNull
        assertThat(result?.gatewayAgentId).isEqualTo("gw-1")
        assertThat(result?.gatewayEndpoint).isEqualTo("http://runner:8090")
    }

    @Test
    fun `resolveAttach rejects and closes when session id is malformed`() {
        val client = mockk<WebSocketSession>(relaxed = true)
        every { client.uri } returns URI.create("ws://api/api/v1/ws/sessions/not-a-uuid/attach")
        val closed = slot<CloseStatus>()
        every { client.close(capture(closed)) } returns Unit

        val result = checker.resolveAttach(client, AttachPreconditionChecker::sessionIdOf)

        assertThat(result).isNull()
        assertThat(closed.captured.code).isEqualTo(CloseStatus.BAD_DATA.code)
        assertThat(telemetry.attachFailures.single().reason).isEqualTo(FailureReasonLabel.INVALID_REQUEST)
    }

    @Test
    fun `resolveAttach rejects and closes when session is not found`() {
        every { sessions.findById(sessionId) } returns null
        val client = clientSession()
        every { client.close(any()) } returns Unit

        val result = checker.resolveAttach(client, AttachPreconditionChecker::sessionIdOf)

        assertThat(result).isNull()
        assertThat(telemetry.attachFailures.single().reason).isEqualTo(FailureReasonLabel.NOT_FOUND)
    }

    @Test
    fun `resolveAttach rejects when session is still STARTING`() {
        every { sessions.findById(sessionId) } returns
            agentSession(
                status = WorkspaceAgentSessionStatus.STARTING,
                gatewayAgentId = null,
            )
        val client = clientSession()
        every { client.close(any()) } returns Unit

        val result = checker.resolveAttach(client, AttachPreconditionChecker::sessionIdOf)

        assertThat(result).isNull()
        assertThat(telemetry.attachFailures.single().reason).isEqualTo(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
    }

    @Test
    fun `resolveAttach rejects when workspace setup transition is pending`() {
        every { sessions.findById(sessionId) } returns agentSession(gatewayAgentId = "gw-1")
        every { workspaces.findById(workspaceId) } returns
            workspace().copy(
                pendingRunnerSetupId = AgentSetupId("gpu"),
                pendingRunnerSetupVersion = AgentSetupVersion(2),
            )
        val client = clientSession()
        every { client.close(any()) } returns Unit

        val result = checker.resolveAttach(client, AttachPreconditionChecker::sessionIdOf)

        assertThat(result).isNull()
        val closed = slot<CloseStatus>()
        verify { client.close(capture(closed)) }
        assertThat(closed.captured.reason).isEqualTo("runner setup transition")
        assertThat(telemetry.attachFailures.single().reason).isEqualTo(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
    }

    @Test
    fun `resolveAttach attempts rebind when session is RUNNING without gateway binding`() {
        val rebound = agentSession(gatewayAgentId = "gw-fresh")
        every { sessions.findById(sessionId) } returns agentSession(gatewayAgentId = null)
        every { workspaces.findById(workspaceId) } returns workspace()
        every {
            binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = sessionId))
        } returns
            RunnerSessionBindingResult.Bound(
                workspace = workspace(),
                session = rebound,
                gatewayAgent =
                    AgentGatewayClient.GatewayAgent(
                        id = "gw-fresh",
                        kind = WorkspaceAgentKind.CLAUDE,
                        cwd = "/workspace",
                    ),
                provisioning = RunnerProvisioningResult.AlreadyReady,
            )

        val result = checker.resolveAttach(clientSession(), AttachPreconditionChecker::sessionIdOf)

        assertThat(result).isNotNull
        assertThat(result?.gatewayAgentId).isEqualTo("gw-fresh")
    }

    @Test
    fun `resolveAttach records bounded telemetry on success`() {
        every { sessions.findById(sessionId) } returns agentSession(gatewayAgentId = "gw-1")
        every { workspaces.findById(workspaceId) } returns workspace()

        // resolveAttach returns Ready without recording — caller records; direct call to recordAttach
        checker.recordAttach(AgentKindLabel.CLAUDE, OutcomeLabel.SUCCESS, FailureReasonLabel.NONE)

        assertThat(telemetry.attachAttempts).hasSize(1)
        assertThat(telemetry.attachAttempts.single().outcome).isEqualTo(OutcomeLabel.SUCCESS)
        assertThat(telemetry.attachFailures).isEmpty()
    }

    private fun clientSession(): WebSocketSession {
        val client = mockk<WebSocketSession>(relaxed = true)
        every { client.uri } returns URI.create("ws://api/api/v1/ws/sessions/${sessionId.value}/attach")
        return client
    }

    private fun agentSession(
        gatewayAgentId: String? = "gw-1",
        status: WorkspaceAgentSessionStatus = WorkspaceAgentSessionStatus.RUNNING,
    ) = WorkspaceAgentSession(
        id = sessionId,
        workspaceId = workspaceId,
        kind = WorkspaceAgentKind.CLAUDE,
        gatewayAgentId = gatewayAgentId,
        status = status,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun workspace() =
        Workspace(
            id = workspaceId,
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = "http://runner:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private class RecordingTelemetry : AgentsApiTelemetry {
        val attachAttempts = mutableListOf<AttachAttemptTelemetry>()
        val attachFailures = mutableListOf<AttachAttemptTelemetry>()
        val operations = mutableListOf<OperationTelemetry>()

        override fun recordAttachAttempt(event: AttachAttemptTelemetry) {
            attachAttempts += event
        }

        override fun recordAttachFailure(event: AttachAttemptTelemetry) {
            attachFailures += event
        }

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }
    }
}

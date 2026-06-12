package com.jorisjonkers.personalstack.agents.infrastructure.ws

import com.jorisjonkers.personalstack.agents.application.idle.ConnectedClientTracker
import com.jorisjonkers.personalstack.agents.application.idle.WorkspaceActivityTracker
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.AttachAttemptTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.sessionbinding.EnsureRunnerSessionBoundInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerProvisioningResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
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
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.TurnRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class SessionAttachHandlerTest {
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

    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val turns = mockk<TurnRepository>(relaxed = true)
    private val activity = mockk<WorkspaceActivityTracker>(relaxed = true)
    private val binding = mockk<RunnerSessionBindingService>()
    private val sessionStatus = mockk<SessionStatusPublisher>(relaxed = true)

    private lateinit var handler: SessionAttachHandler
    private lateinit var upstream: WebSocketSession

    private val sessionId = WorkspaceAgentSessionId.random()
    private val workspaceId = WorkspaceId.random()
    private lateinit var telemetry: RecordingTelemetry

    @BeforeEach
    fun setUp() {
        // The handler builds its own StandardWebSocketClient; stub the
        // outbound attach so afterConnectionEstablished yields a bridge
        // whose upstream is a mock we can assert against.
        mockkConstructor(StandardWebSocketClient::class)
        upstream = mockk(relaxed = true)
        every { upstream.isOpen } returns true
        every {
            anyConstructed<StandardWebSocketClient>().execute(any(), any<String>())
        } returns CompletableFuture.completedFuture(upstream)

        telemetry = RecordingTelemetry()
        handler =
            SessionAttachHandler(
                sessions,
                workspaces,
                activity,
                ConnectedClientTracker(),
                binding,
                sessionStatus,
                telemetry,
            )

        every { sessions.findById(sessionId) } returns agentSession()
        every { workspaces.findById(workspaceId) } returns workspace()
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(StandardWebSocketClient::class)
    }

    @Test
    fun `client input frame relays upstream verbatim and persists no turns`() {
        val client = clientSession()
        handler.afterConnectionEstablished(client)

        val frame = TextMessage("""{"input":"ls -la\r","enter":false}""")
        handler.handleMessage(client, frame)

        val sent = slot<TextMessage>()
        verify { upstream.sendMessage(capture(sent)) }
        assertThat(sent.captured.payload).isEqualTo(frame.payload)
        verify(exactly = 0) { turns.save(any()) }
    }

    @Test
    fun `client resize frame is forwarded upstream verbatim`() {
        val client = clientSession()
        handler.afterConnectionEstablished(client)

        val frame = TextMessage("""{"resize":{"cols":120,"rows":40}}""")
        handler.handleMessage(client, frame)

        val sent = slot<TextMessage>()
        verify { upstream.sendMessage(capture(sent)) }
        assertThat(sent.captured.payload).isEqualTo(frame.payload)
        verify(exactly = 0) { turns.save(any()) }
    }

    @Test
    fun `upstream output frame relays to the browser without persisting turns`() {
        val client = mockk<WebSocketSession>(relaxed = true)
        every { client.id } returns UUID.randomUUID().toString()
        every { client.uri } returns attachUri()
        every { client.isOpen } returns true

        // Capture the upstream handler the gateway frames arrive on.
        val handlerSlot = slot<org.springframework.web.socket.WebSocketHandler>()
        every {
            anyConstructed<StandardWebSocketClient>().execute(capture(handlerSlot), any<String>())
        } returns CompletableFuture.completedFuture(upstream)

        handler.afterConnectionEstablished(client)

        val frame = TextMessage("""{"output":"[2J[Hhello"}""")
        handlerSlot.captured.handleMessage(upstream, frame)

        val sent = slot<TextMessage>()
        verify { client.sendMessage(capture(sent)) }
        assertThat(sent.captured.payload).isEqualTo(frame.payload)
        verify(exactly = 0) { turns.save(any()) }
    }

    @Test
    fun `closing the client closes the upstream and persists no turns`() {
        every { upstream.close(any()) } just Runs
        val client = clientSession()
        handler.afterConnectionEstablished(client)

        handler.afterConnectionClosed(client, CloseStatus.NORMAL)

        verify { upstream.close(CloseStatus.NORMAL) }
        verify(exactly = 0) { turns.save(any()) }
        // A second relay after close is a no-op (bridge already removed).
        handler.handleMessage(client, TextMessage("""{"input":"x"}"""))
        verify(exactly = 1) { upstream.close(any()) }
    }

    @Test
    fun `upstream close closes browser socket so it can reconnect`() {
        val client = clientSession()
        every { client.close(any()) } just Runs
        val handlerSlot = slot<org.springframework.web.socket.WebSocketHandler>()
        every {
            anyConstructed<StandardWebSocketClient>().execute(capture(handlerSlot), any<String>())
        } returns CompletableFuture.completedFuture(upstream)

        handler.afterConnectionEstablished(client)
        handlerSlot.captured.afterConnectionClosed(upstream, CloseStatus.GOING_AWAY)

        val closed = slot<CloseStatus>()
        verify { client.close(capture(closed)) }
        assertThat(closed.captured.reason).isEqualTo("runner attach disconnected")
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.CANCELLED)
        assertBoundedTelemetryLabels()
    }

    @Test
    fun `upstream transport error closes browser socket so it can reconnect`() {
        val client = clientSession()
        every { client.close(any()) } just Runs
        every { upstream.close(any()) } just Runs
        val handlerSlot = slot<org.springframework.web.socket.WebSocketHandler>()
        every {
            anyConstructed<StandardWebSocketClient>().execute(capture(handlerSlot), any<String>())
        } returns CompletableFuture.completedFuture(upstream)

        handler.afterConnectionEstablished(client)
        handlerSlot.captured.handleTransportError(upstream, RuntimeException("boom $workspaceId $sessionId"))

        val closed = slot<CloseStatus>()
        verify { client.close(capture(closed)) }
        verify { upstream.close(match { it.reason == "runner attach error" }) }
        assertThat(closed.captured.reason).isEqualTo("runner attach error")
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.IO_ERROR)
        assertBoundedTelemetryLabels("boom", workspaceId.value.toString(), sessionId.value.toString())
    }

    @Test
    fun `valid reconnect cursor query is forwarded to upstream attach uri`() {
        val uriSlot = slot<String>()
        every {
            anyConstructed<StandardWebSocketClient>().execute(any(), capture(uriSlot))
        } returns CompletableFuture.completedFuture(upstream)

        handler.afterConnectionEstablished(clientSession("?epoch=7&offset=12"))

        assertThat(uriSlot.captured).isEqualTo("ws://gw:8090/ws/agents/abc12345/attach?epoch=7&offset=12")
    }

    @Test
    fun `epoch and offset query values are forwarded unchanged to upstream attach uri`() {
        val uriSlot = slot<String>()
        every {
            anyConstructed<StandardWebSocketClient>().execute(any(), capture(uriSlot))
        } returns CompletableFuture.completedFuture(upstream)

        handler.afterConnectionEstablished(clientSession("?offset=00012&epoch=0007"))

        assertThat(uriSlot.captured).isEqualTo("ws://gw:8090/ws/agents/abc12345/attach?epoch=0007&offset=00012")
    }

    @Test
    fun `invalid reconnect cursor query is omitted instead of closing bad data`() {
        val uriSlot = slot<String>()
        every {
            anyConstructed<StandardWebSocketClient>().execute(any(), capture(uriSlot))
        } returns CompletableFuture.completedFuture(upstream)

        handler.afterConnectionEstablished(clientSession("?epoch=-1&offset=nope"))

        assertThat(uriSlot.captured).isEqualTo("ws://gw:8090/ws/agents/abc12345/attach")
    }

    @Test
    fun `running session without gateway binding is rebound before upstream attach`() {
        val rebound = agentSession(gatewayAgentId = "fresh")
        every { sessions.findById(sessionId) } returns agentSession(gatewayAgentId = null)
        every {
            binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = sessionId))
        } returns bound(rebound)
        val uriSlot = slot<String>()
        every {
            anyConstructed<StandardWebSocketClient>().execute(any(), capture(uriSlot))
        } returns CompletableFuture.completedFuture(upstream)

        handler.afterConnectionEstablished(clientSession())

        assertThat(uriSlot.captured).isEqualTo("ws://gw:8090/ws/agents/fresh/attach")
    }

    @Test
    fun `attach closes while workspace setup transition is pending`() {
        val client = clientSession()
        every { client.close(any()) } just Runs
        every { workspaces.findById(workspaceId) } returns
            workspace()
                .copy(
                    pendingRunnerSetupId = AgentSetupId("gpu"),
                    pendingRunnerSetupVersion = AgentSetupVersion(2),
                )

        handler.afterConnectionEstablished(client)

        val closed = slot<CloseStatus>()
        verify { client.close(capture(closed)) }
        assertThat(closed.captured.reason).isEqualTo("runner setup transition")
        assertThat(telemetry.attachFailures.single().reason).isEqualTo(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
        assertBoundedTelemetryLabels(workspaceId.value.toString(), sessionId.value.toString(), "gpu")
        verify(exactly = 0) { anyConstructed<StandardWebSocketClient>().execute(any(), any<String>()) }
    }

    @Test
    fun `unknown upstream agent clears stale binding and asks binder to rebind`() {
        val client = clientSession()
        every { client.close(any()) } just Runs
        every { sessions.clearGatewayBindingIfGeneration(sessionId, 0, any()) } returns true
        every {
            binding.ensureBound(EnsureRunnerSessionBoundInput(sessionId = sessionId, workspaceId = workspaceId))
        } returns bound(agentSession(gatewayAgentId = "fresh"))
        val handlerSlot = slot<org.springframework.web.socket.WebSocketHandler>()
        every {
            anyConstructed<StandardWebSocketClient>().execute(capture(handlerSlot), any<String>())
        } returns CompletableFuture.completedFuture(upstream)

        handler.afterConnectionEstablished(client)
        handlerSlot.captured.afterConnectionClosed(upstream, CloseStatus.BAD_DATA.withReason("unknown agent"))

        verify { sessions.clearGatewayBindingIfGeneration(sessionId, 0, any()) }
        verify { sessionStatus.publishStatus(match { it.id == sessionId && it.gatewayAgentId == null }, false) }
        verify {
            binding.ensureBound(
                EnsureRunnerSessionBoundInput(sessionId = sessionId, workspaceId = workspaceId),
            )
        }
        val closed = slot<CloseStatus>()
        verify { client.close(capture(closed)) }
        assertThat(closed.captured.reason).isEqualTo("runner attach disconnected")
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.INVALID_REQUEST)
        assertBoundedTelemetryLabels("unknown agent", workspaceId.value.toString(), sessionId.value.toString())
    }

    @Test
    fun `client input after upstream closed closes browser socket instead of dropping keystrokes`() {
        val client = clientSession()
        every { client.close(any()) } just Runs
        handler.afterConnectionEstablished(client)
        every { upstream.isOpen } returns false

        handler.handleMessage(client, TextMessage("""{"input":"x","enter":false}"""))

        val closed = slot<CloseStatus>()
        verify { client.close(capture(closed)) }
        assertThat(closed.captured.reason).isEqualTo("runner attach disconnected")
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
        assertBoundedTelemetryLabels("runner attach disconnected")
    }

    @Test
    fun `upstream handshake failure records bounded reason and does not leak exception labels`() {
        val client = clientSession()
        every { client.close(any()) } just Runs
        val rawMessage = "dial failed for $workspaceId $sessionId"
        val failed = CompletableFuture<WebSocketSession>()
        failed.completeExceptionally(RuntimeException(rawMessage))
        every {
            anyConstructed<StandardWebSocketClient>().execute(any(), any<String>())
        } returns failed

        handler.afterConnectionEstablished(client)

        val closed = slot<CloseStatus>()
        verify { client.close(capture(closed)) }
        assertThat(closed.captured.reason).isEqualTo("runner attach unavailable")
        assertThat(telemetry.attachFailures.single().reason).isEqualTo(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
        assertBoundedTelemetryLabels(rawMessage, workspaceId.value.toString(), sessionId.value.toString())
    }

    @Test
    fun `client relay transport error records bounded reason and closes started resources`() {
        val client = clientSession()
        every { client.close(any()) } just Runs
        every { upstream.close(any()) } just Runs
        every { upstream.sendMessage(any()) } throws RuntimeException("write failed for $sessionId")

        handler.afterConnectionEstablished(client)
        handler.handleMessage(client, TextMessage("""{"input":"x","enter":false}"""))

        verify { client.close(match { it.reason == "runner attach error" }) }
        verify { upstream.close(match { it.reason == "runner attach error" }) }
        assertThat(telemetry.operations.map { it.reason }).contains(FailureReasonLabel.IO_ERROR)
        assertBoundedTelemetryLabels("write failed", sessionId.value.toString(), workspaceId.value.toString())
    }

    private fun assertBoundedTelemetryLabels(vararg forbidden: String) {
        val labels =
            telemetry.attachAttempts.flatMap {
                listOf(it.kind.label, it.runMode.label, it.outcome.label, it.reason.label)
            } +
                telemetry.attachFailures.flatMap {
                    listOf(it.kind.label, it.runMode.label, it.outcome.label, it.reason.label)
                } +
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
        assertThat(labels).allSatisfy { label -> assertThat(label).doesNotContain(sessionId.value.toString()) }
        assertThat(labels).allSatisfy { label -> assertThat(label).doesNotContain(workspaceId.value.toString()) }
        assertThat(telemetry.attachAttempts.map { it.outcome }).allSatisfy {
            assertThat(it).isIn(*OutcomeLabel.values())
        }
    }

    private fun clientSession(query: String = ""): WebSocketSession {
        val client = mockk<WebSocketSession>(relaxed = true)
        every { client.id } returns UUID.randomUUID().toString()
        every { client.uri } returns attachUri(query)
        every { client.isOpen } returns true
        return client
    }

    private fun attachUri(query: String = ""): URI =
        URI.create("ws://api/api/v1/ws/sessions/${sessionId.value}/attach$query")

    private fun bound(session: WorkspaceAgentSession) =
        RunnerSessionBindingResult.Bound(
            workspace = workspace(),
            session = session,
            gatewayAgent =
                AgentGatewayClient.GatewayAgent(
                    id = session.gatewayAgentId ?: "fresh",
                    kind = session.kind,
                    cwd = "/workspace",
                    stableSessionId = session.stableSessionId,
                    epoch = session.epoch,
                ),
            provisioning = RunnerProvisioningResult.AlreadyReady,
        )

    private fun agentSession(gatewayAgentId: String? = "abc12345") =
        WorkspaceAgentSession(
            id = sessionId,
            workspaceId = workspaceId,
            kind = WorkspaceAgentKind.CLAUDE,
            gatewayAgentId = gatewayAgentId,
            status = WorkspaceAgentSessionStatus.RUNNING,
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
            gatewayEndpoint = "http://gw:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

package com.jorisjonkers.personalstack.agents.infrastructure.ws

import com.jorisjonkers.personalstack.agents.application.idle.ConnectedClientTracker
import com.jorisjonkers.personalstack.agents.application.idle.WorkspaceActivityTracker
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
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
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val turns = mockk<TurnRepository>(relaxed = true)
    private val activity = mockk<WorkspaceActivityTracker>(relaxed = true)

    private lateinit var handler: SessionAttachHandler
    private lateinit var upstream: WebSocketSession

    private val sessionId = WorkspaceAgentSessionId.random()
    private val workspaceId = WorkspaceId.random()

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

        handler = SessionAttachHandler(sessions, workspaces, activity, ConnectedClientTracker())

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
    }

    @Test
    fun `upstream transport error closes browser socket so it can reconnect`() {
        val client = clientSession()
        every { client.close(any()) } just Runs
        val handlerSlot = slot<org.springframework.web.socket.WebSocketHandler>()
        every {
            anyConstructed<StandardWebSocketClient>().execute(capture(handlerSlot), any<String>())
        } returns CompletableFuture.completedFuture(upstream)

        handler.afterConnectionEstablished(client)
        handlerSlot.captured.handleTransportError(upstream, RuntimeException("boom"))

        val closed = slot<CloseStatus>()
        verify { client.close(capture(closed)) }
        assertThat(closed.captured.reason).isEqualTo("runner attach error")
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
    }

    private fun clientSession(): WebSocketSession {
        val client = mockk<WebSocketSession>(relaxed = true)
        every { client.id } returns UUID.randomUUID().toString()
        every { client.uri } returns attachUri()
        every { client.isOpen } returns true
        return client
    }

    private fun attachUri(): URI = URI.create("ws://api/api/v1/ws/sessions/${sessionId.value}/attach")

    private fun agentSession() =
        WorkspaceAgentSession(
            id = sessionId,
            workspaceId = workspaceId,
            kind = WorkspaceAgentKind.CLAUDE,
            gatewayAgentId = "abc12345",
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

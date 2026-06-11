package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.rag.ContextBuilder
import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.TurnRole
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SendUserInputCommandHandlerTest {
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val turns = mockk<TurnRepository>(relaxed = true)
    private val gateway = mockk<AgentGatewayClient>(relaxed = true)
    private val contextBuilder =
        mockk<ContextBuilder> {
            every { augment(any()) } answers { firstArg() }
        }
    private val handler = SendUserInputCommandHandler(sessions, workspaces, turns, gateway, contextBuilder)

    @Test
    fun `handle persists user turn and forwards input to the gateway`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = "abc12345")
        every { sessions.findById(session.id) } returns session
        every { workspaces.findById(ws.id) } returns ws
        val savedTurn = slot<Turn>()
        every { turns.save(capture(savedTurn)) } answers { savedTurn.captured }

        handler.handle(SendUserInputCommand(sessionId = session.id, text = "hello", enter = true))

        assertThat(savedTurn.captured.role).isEqualTo(TurnRole.USER)
        assertThat(savedTurn.captured.body).isEqualTo("hello")
        verify { gateway.sendInput(ws, "abc12345", "hello", true) }
    }

    @Test
    fun `handle forwards the augmented prompt while persisting the raw user text`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = "abc12345")
        every { sessions.findById(session.id) } returns session
        every { workspaces.findById(ws.id) } returns ws
        val savedTurn = slot<Turn>()
        every { turns.save(capture(savedTurn)) } answers { savedTurn.captured }
        every { contextBuilder.augment("hello") } returns "<context>kb hit</context>\n\nhello"

        handler.handle(SendUserInputCommand(sessionId = session.id, text = "hello"))

        // Stored turn is the original user text (no RAG bleed-through into history)
        assertThat(savedTurn.captured.body).isEqualTo("hello")
        // Gateway gets the augmented form
        verify { gateway.sendInput(ws, "abc12345", "<context>kb hit</context>\n\nhello", true) }
    }

    @Test
    fun `handle throws when the session has not bound a gateway agent yet`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = null)
        every { sessions.findById(session.id) } returns session
        every { workspaces.findById(ws.id) } returns ws

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            handler.handle(SendUserInputCommand(sessionId = session.id, text = "x"))
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
        status =
            if (gatewayAgentId != null) {
                WorkspaceAgentSessionStatus.RUNNING
            } else {
                WorkspaceAgentSessionStatus.STARTING
            },
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.rag.LessonAutoCapture
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
import java.time.Instant

class StopAgentSessionCommandHandlerTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val gateway = mockk<AgentGatewayClient>(relaxed = true)
    private val autoCapture = mockk<LessonAutoCapture>(relaxed = true)
    private val handler = StopAgentSessionCommandHandler(workspaces, sessions, gateway, autoCapture)

    @Test
    fun `handle stops gateway agent and persists stopped state`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = "abc12345")
        every { sessions.findById(session.id) } returns session
        every { workspaces.findById(ws.id) } returns ws
        val saved = slot<WorkspaceAgentSession>()
        every { sessions.save(capture(saved)) } answers { saved.captured }

        handler.handle(StopAgentSessionCommand(session.id))

        verify { gateway.stopAgent(ws, "abc12345") }
        verify { autoCapture.capture(session.id) }
        assertThat(saved.captured.status).isEqualTo(WorkspaceAgentSessionStatus.STOPPED)
    }

    @Test
    fun `handle is a no-op for unknown session`() {
        val id = WorkspaceAgentSessionId.random()
        every { sessions.findById(id) } returns null
        handler.handle(StopAgentSessionCommand(id))
        verify(exactly = 0) { gateway.stopAgent(any(), any()) }
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
    )
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class StartHeadlessJobCommandHandlerTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val gateway = mockk<AgentGatewayClient>()
    private val orchestrator = mockk<AgentRunnerOrchestrator>()
    private val handler = StartHeadlessJobCommandHandler(workspaces, sessions, gateway, orchestrator)

    @Test
    fun `handle launches headless job and persists session when runner is ready`() {
        val ws = workspace()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(ws) } returns true
        every {
            gateway.startHeadlessJob(ws, WorkspaceAgentKind.CLAUDE, "write tests", null, null)
        } returns
            AgentGatewayClient.HeadlessJob(
                id = "hls-abc123",
                status = AgentGatewayClient.HeadlessStatus.RUNNING,
                exitCode = null,
                output = null,
            )
        val saved = slot<WorkspaceAgentSession>()
        every { sessions.save(capture(saved)) } answers { firstArg() }

        handler.handle(
            StartHeadlessJobCommand(
                sessionId = WorkspaceAgentSessionId.random(),
                workspaceId = ws.id,
                kind = WorkspaceAgentKind.CLAUDE,
                prompt = "write tests",
            ),
        )

        assertThat(saved.captured.gatewayAgentId).isEqualTo("hls-abc123")
        assertThat(saved.captured.status).isEqualTo(WorkspaceAgentSessionStatus.RUNNING)
        verify(exactly = 0) { orchestrator.destroy(any()) }
        verify(exactly = 0) { orchestrator.provision(any()) }
    }

    @Test
    fun `handle raises NoSuchElementException when workspace does not exist`() {
        val missingId = WorkspaceId.random()
        every { workspaces.findById(missingId) } returns null

        val ex =
            assertThrows<NoSuchElementException> {
                handler.handle(
                    StartHeadlessJobCommand(
                        sessionId = WorkspaceAgentSessionId.random(),
                        workspaceId = missingId,
                        kind = WorkspaceAgentKind.CLAUDE,
                        prompt = "hello",
                    ),
                )
            }
        assertThat(ex.message).contains(missingId.value.toString())
    }

    private fun workspace() =
        Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = "agent-runner-abcdef01",
            pvcName = "workspace-abcdef01",
            gatewayEndpoint = "http://x:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

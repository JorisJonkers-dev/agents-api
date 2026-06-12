package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.query.GetTurnHistoryQueryService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RestartAgentSessionInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RestartAgentSessionService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerProvisioningResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
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
import com.jorisjonkers.personalstack.common.command.CommandBus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class AgentSessionControllerTest {
    private val commandBus = mockk<CommandBus>(relaxed = true)
    private val turnHistory = mockk<GetTurnHistoryQueryService>(relaxed = true)
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val gateway = mockk<AgentGatewayClient>()
    private val restartAgentSession = mockk<RestartAgentSessionService>()
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AgentSessionController(commandBus, turnHistory, sessions, workspaces, gateway, restartAgentSession),
            ).build()

    private val workspaceId = WorkspaceId.random()
    private val sessionId = WorkspaceAgentSessionId.random()

    @Test
    fun `POST staged-inputs forwards to gateway and returns file metadata`() {
        val workspace = workspace()
        every { sessions.findById(sessionId) } returns agentSession()
        every { workspaces.findById(workspaceId) } returns workspace
        every {
            gateway.stageInput(workspace, "abc12345", "large document", "source.txt")
        } returns
            AgentGatewayClient.StagedInput(
                path = "/workspace/.agent-inputs/20260604-source.txt",
                bytes = 14,
                name = "source.txt",
            )

        mockMvc
            .perform(
                post("/api/v1/workspaces/${workspaceId.value}/sessions/${sessionId.value}/staged-inputs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"large document","name":"source.txt"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.path").value("/workspace/.agent-inputs/20260604-source.txt"))
            .andExpect(jsonPath("$.bytes").value(14))
            .andExpect(jsonPath("$.name").value("source.txt"))

        verify { gateway.stageInput(workspace, "abc12345", "large document", "source.txt") }
    }

    @Test
    fun `POST restart returns accepted durable session metadata`() {
        val workspace = workspace()
        val restarted = agentSession().copy(epoch = 3, generation = 7)
        every {
            restartAgentSession.restart(
                RestartAgentSessionInput(
                    workspaceId = workspaceId,
                    sessionId = sessionId,
                    expectedGeneration = 6,
                ),
            )
        } returns
            RunnerSessionBindingResult.Bound(
                workspace = workspace,
                session = restarted,
                gatewayAgent =
                    AgentGatewayClient.GatewayAgent(
                        id = "fresh",
                        kind = WorkspaceAgentKind.CLAUDE,
                        cwd = "/workspace",
                        epoch = 3,
                    ),
                provisioning = RunnerProvisioningResult.AlreadyReady,
            )

        mockMvc
            .perform(
                post("/api/v1/workspaces/${workspaceId.value}/sessions/${sessionId.value}/restart")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedGeneration":6}"""),
            ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.sessionId").value(sessionId.value.toString()))
            .andExpect(jsonPath("$.epoch").value(3))
            .andExpect(jsonPath("$.generation").value(7))
            .andExpect(jsonPath("$.status").value("RUNNING"))
    }

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

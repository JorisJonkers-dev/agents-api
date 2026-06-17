package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.query.GetTurnHistoryQueryService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RestartAgentSessionInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RestartAgentSessionService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerProvisioningResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
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
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.WorkspaceAgentSessionResponse
import com.jorisjonkers.personalstack.common.command.CommandBus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class AgentSessionControllerTest {
    private val now = Instant.parse("2026-06-12T12:00:00Z")
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
            ).setControllerAdvice(AgentRunnerUnavailableExceptionHandler())
            .build()

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
    fun `POST staged-inputs returns 503 when session has no gateway binding`() {
        every { sessions.findById(sessionId) } returns agentSession().copy(gatewayAgentId = null)
        every { workspaces.findById(workspaceId) } returns workspace()

        mockMvc
            .perform(
                post("/api/v1/workspaces/${workspaceId.value}/sessions/${sessionId.value}/staged-inputs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"large document","name":"source.txt"}"""),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.runnerStatus").value(RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label))
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
            .andExpect(jsonPath("$.currentSetup.id").value("default"))
            .andExpect(jsonPath("$.currentSetup.version").value(1))
    }

    @Test
    fun `POST restart forwards target setup and accepts epoch and current setup preconditions`() {
        val workspace = workspace()
        val current = agentSession().copy(epoch = 2, generation = 6)
        val restarted =
            current.copy(
                epoch = 3,
                generation = 7,
                currentSetupId = AgentSetupId("gpu"),
                currentSetupVersion = AgentSetupVersion(2),
            )
        every { sessions.findById(sessionId) } returns current
        every {
            restartAgentSession.restart(
                RestartAgentSessionInput(
                    workspaceId = workspaceId,
                    sessionId = sessionId,
                    expectedGeneration = 6,
                    targetSetupId = AgentSetupId("gpu"),
                    targetSetupVersion = AgentSetupVersion(2),
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
                    .content(
                        """
                        {
                          "expectedGeneration": 6,
                          "expectedEpoch": 2,
                          "expectedSetupId": "default",
                          "expectedSetupVersion": 1,
                          "targetSetupId": "gpu",
                          "targetSetupVersion": 2
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.currentSetup.id").value("gpu"))
            .andExpect(jsonPath("$.currentSetup.version").value(2))
    }

    @Test
    fun `POST restart returns conflict when expected epoch is stale`() {
        val current = agentSession().copy(epoch = 3, generation = 7)
        every { sessions.findById(sessionId) } returns current

        mockMvc
            .perform(
                post("/api/v1/workspaces/${workspaceId.value}/sessions/${sessionId.value}/restart")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedEpoch":2}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.sessionId").value(sessionId.value.toString()))
            .andExpect(jsonPath("$.epoch").value(3))
            .andExpect(jsonPath("$.generation").value(7))
            .andExpect(jsonPath("$.status").value("RUNNING"))

        verify(exactly = 0) { restartAgentSession.restart(any()) }
    }

    @Test
    fun `POST restart returns conflict with current durable session metadata`() {
        val current = agentSession().copy(epoch = 3, generation = 7)
        every {
            restartAgentSession.restart(
                RestartAgentSessionInput(
                    workspaceId = workspaceId,
                    sessionId = sessionId,
                    expectedGeneration = 6,
                ),
            )
        } returns RunnerSessionBindingResult.Conflict(current)

        mockMvc
            .perform(
                post("/api/v1/workspaces/${workspaceId.value}/sessions/${sessionId.value}/restart")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedGeneration":6}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.sessionId").value(sessionId.value.toString()))
            .andExpect(jsonPath("$.epoch").value(3))
            .andExpect(jsonPath("$.generation").value(7))
            .andExpect(jsonPath("$.status").value("RUNNING"))
    }

    @Test
    fun `workspace agent session response includes lifecycle metadata and derived idle`() {
        val boundAt = Instant.parse("2026-06-12T12:00:05Z")
        val running =
            WorkspaceAgentSessionResponse.of(
                agentSession().copy(epoch = 4, generation = 9, gatewayBoundAt = boundAt),
            )
        val idle =
            WorkspaceAgentSessionResponse.of(
                agentSession().copy(gatewayAgentId = null, gatewayBoundAt = null),
            )

        assertThat(running.epoch).isEqualTo(4)
        assertThat(running.generation).isEqualTo(9)
        assertThat(running.gatewayBoundAt).isEqualTo(boundAt)
        assertThat(running.idle).isFalse()
        assertThat(running.currentSetup.id).isEqualTo("default")
        assertThat(running.currentSetup.version).isEqualTo(1)
        assertThat(idle.idle).isTrue()
    }

    private fun agentSession() =
        WorkspaceAgentSession(
            id = sessionId,
            workspaceId = workspaceId,
            kind = WorkspaceAgentKind.CLAUDE,
            gatewayAgentId = "abc12345",
            status = WorkspaceAgentSessionStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
            epoch = 1,
            generation = 0,
            gatewayBoundAt = now,
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
            createdAt = now,
            updatedAt = now,
        )
}

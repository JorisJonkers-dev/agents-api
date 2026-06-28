package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssue
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssueCode
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
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
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RestartAgentSessionServiceTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val binding = mockk<RunnerSessionBindingService>()
    private val setupValidation = mockk<AgentSetupValidationService>()
    private val clock = Clock.fixed(Instant.parse("2026-06-12T09:00:00Z"), ZoneOffset.UTC)
    private val telemetry = RecordingAgentsApiTelemetry()
    private val service = RestartAgentSessionService(workspaces, sessions, binding, setupValidation, clock, telemetry)

    init {
        every { setupValidation.requireValid(any()) } answers {
            val request =
                firstArg<com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationInput>()
            AgentSetupValidationResult(
                target = AgentSetupRef(request.targetId, request.targetVersion),
                valid = true,
            )
        }
    }

    @Test
    fun `restart delegates running interactive session with expected generation`() {
        val ws = workspace()
        val session = session(ws.id, status = WorkspaceAgentSessionStatus.RUNNING).copy(generation = 4)
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session
        every {
            binding.restart(
                RestartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    expectedGeneration = 4,
                    reason = "manual",
                    targetSetupId = session.currentSetupId,
                    targetSetupVersion = session.currentSetupVersion,
                ),
            )
        } returns bound(ws, session)

        val result =
            service.restart(
                RestartAgentSessionInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    expectedGeneration = 4,
                    reason = "manual",
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        verify { setupValidation.requireValid(any()) }
        verify { binding.restart(any()) }
    }

    @Test
    fun `restart allows retained stopped session before cleanup expiry`() {
        val ws = workspace()
        val session =
            session(ws.id, status = WorkspaceAgentSessionStatus.STOPPED)
                .copy(retainedUntil = Instant.parse("2026-06-12T10:00:00Z"))
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session
        every { binding.restart(any()) } returns bound(ws, session)

        service.restart(RestartAgentSessionInput(workspaceId = ws.id, sessionId = session.id))

        verify { binding.restart(any()) }
    }

    @Test
    fun `restart rejects expired stopped session`() {
        val ws = workspace()
        val session =
            session(ws.id, status = WorkspaceAgentSessionStatus.STOPPED)
                .copy(retainedUntil = Instant.parse("2026-06-12T08:59:59Z"))
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session

        assertThrows<IllegalArgumentException> {
            service.restart(RestartAgentSessionInput(workspaceId = ws.id, sessionId = session.id))
        }
    }

    @Test
    fun `restart rejects non-interactive sessions`() {
        val ws = workspace()
        val session =
            session(ws.id, status = WorkspaceAgentSessionStatus.RUNNING)
                .copy(runMode = "HEADLESS")
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session

        assertThrows<IllegalArgumentException> {
            service.restart(RestartAgentSessionInput(workspaceId = ws.id, sessionId = session.id))
        }
    }

    @Test
    fun `restart rejects sessions from a different workspace`() {
        val ws = workspace()
        val session = session(WorkspaceId.random(), status = WorkspaceAgentSessionStatus.RUNNING)
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session

        assertThrows<IllegalArgumentException> {
            service.restart(RestartAgentSessionInput(workspaceId = ws.id, sessionId = session.id))
        }
    }

    @Test
    fun `restart returns conflict when expected generation is stale`() {
        telemetry.operations.clear()
        val ws = workspace()
        val session = session(ws.id, status = WorkspaceAgentSessionStatus.RUNNING).copy(generation = 9)
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session

        val result =
            service.restart(
                RestartAgentSessionInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    expectedGeneration = 8,
                ),
            )

        assertThat(result).isEqualTo(RunnerSessionBindingResult.Conflict(current = session))
        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.outcome).isEqualTo(OutcomeLabel.FAILURE)
                assertThat(it.reason).isEqualTo(FailureReasonLabel.CAPACITY)
            }
    }

    @Test
    fun `restart validation rejection records bounded reason without raw validation message`() {
        telemetry.operations.clear()
        val ws = workspace()
        val session = session(ws.id, status = WorkspaceAgentSessionStatus.RUNNING).copy(generation = 4)
        val targetId = AgentSetupId("target")
        val targetVersion = AgentSetupVersion(2)
        val result =
            AgentSetupValidationResult(
                target = AgentSetupRef(targetId, targetVersion),
                valid = false,
                issues =
                    listOf(
                        AgentSetupValidationIssue(
                            code = AgentSetupValidationIssueCode.SECRET_MISSING,
                            message = "Secret agents/raw-private-token is missing",
                        ),
                    ),
            )
        every { workspaces.findById(ws.id) } returns ws
        every { sessions.findById(session.id) } returns session
        every {
            setupValidation.requireValid(
                match {
                    it.targetId == targetId &&
                        it.targetVersion == targetVersion &&
                        it.session == session
                },
            )
        } throws AgentSetupValidationException(result)

        assertThrows<AgentSetupValidationException> {
            service.restart(
                RestartAgentSessionInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    targetSetupId = targetId,
                    targetSetupVersion = targetVersion,
                ),
            )
        }

        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.outcome).isEqualTo(OutcomeLabel.FAILURE)
                assertThat(it.reason).isEqualTo(FailureReasonLabel.INVALID_REQUEST)
            }
        assertThat(telemetry.operations.map { it.reason.label })
            .doesNotContain("Secret agents/raw-private-token is missing")
    }

    private fun bound(
        workspace: Workspace,
        session: WorkspaceAgentSession,
    ) = RunnerSessionBindingResult.Bound(
        workspace = workspace,
        session = session,
        gatewayAgent =
            AgentGatewayClient.GatewayAgent(
                id = "abc12345",
                kind = session.kind,
                cwd = "/workspace",
            ),
        provisioning = RunnerProvisioningResult.AlreadyReady,
    )

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

    private fun session(
        workspaceId: WorkspaceId,
        status: WorkspaceAgentSessionStatus,
    ) = WorkspaceAgentSession(
        id = WorkspaceAgentSessionId.random(),
        workspaceId = workspaceId,
        kind = WorkspaceAgentKind.CLAUDE,
        gatewayAgentId = "abc12345",
        status = status,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private class RecordingAgentsApiTelemetry : AgentsApiTelemetry {
        val operations = mutableListOf<OperationTelemetry>()

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }
    }
}

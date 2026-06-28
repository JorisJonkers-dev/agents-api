package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssue
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssueCode
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.SetupRestartEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SetupTransitionAuditServiceTest {
    private val events = mockk<SetupRestartEventRepository>()
    private val telemetry = RecordingAgentsApiTelemetry()
    private val service = SetupTransitionAuditService(events, telemetry)

    @Test
    fun `persists rejected validation transition after workspace and session are known`() {
        telemetry.operations.clear()
        val now = Instant.parse("2026-06-12T00:00:00Z")
        val workspace = workspace()
        val session = session(workspace)
        val result =
            AgentSetupValidationResult(
                target = AgentSetupRef(AgentSetupId("target"), AgentSetupVersion(2)),
                valid = false,
                issues =
                    listOf(
                        AgentSetupValidationIssue(
                            code = AgentSetupValidationIssueCode.SECRET_MISSING,
                            message = "Secret agents/kb-secret is missing",
                        ),
                    ),
            )
        val saved = slot<SetupRestartEvent>()
        every { events.save(capture(saved)) } answers { firstArg() }

        val event =
            service.recordRejected(
                workspace = workspace,
                session = session,
                targetId = AgentSetupId("target"),
                targetVersion = AgentSetupVersion(2),
                result = result,
                now = now,
            )

        assertThat(event).isSameAs(saved.captured)
        assertThat(saved.captured.workspaceId).isEqualTo(workspace.id)
        assertThat(saved.captured.sessionId).isEqualTo(session.id)
        assertThat(saved.captured.fromSetupId).isEqualTo(session.currentSetupId)
        assertThat(saved.captured.fromSetupVersion).isEqualTo(session.currentSetupVersion)
        assertThat(saved.captured.toSetupId).isEqualTo(AgentSetupId("target"))
        assertThat(saved.captured.toSetupVersion).isEqualTo(AgentSetupVersion(2))
        assertThat(saved.captured.status).isEqualTo(SetupRestartEventStatus.FAILED)
        assertThat(saved.captured.reason).isEqualTo(SetupTransitionAuditService.REASON_VALIDATION_REJECTED)
        assertThat(saved.captured.message).contains("SECRET_MISSING")
        assertThat(saved.captured.completedAt).isEqualTo(now)
        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.outcome).isEqualTo(OutcomeLabel.FAILURE)
                assertThat(it.reason).isEqualTo(FailureReasonLabel.INVALID_REQUEST)
            }
        assertThat(telemetry.operations.map { it.reason.label })
            .doesNotContain("Secret agents/kb-secret is missing")
    }

    @Test
    fun `does not persist valid validation outcomes`() {
        telemetry.operations.clear()
        val result =
            AgentSetupValidationResult(
                target = AgentSetupRef(AgentSetupId("target"), AgentSetupVersion(2)),
                valid = true,
            )

        val event =
            service.recordRejected(
                workspace = workspace(),
                session = null,
                targetId = AgentSetupId("target"),
                targetVersion = AgentSetupVersion(2),
                result = result,
            )

        assertThat(event).isNull()
        verify(exactly = 0) { events.save(any()) }
        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.outcome).isEqualTo(OutcomeLabel.SKIPPED)
                assertThat(it.reason).isEqualTo(FailureReasonLabel.NONE)
            }
    }

    private fun workspace(): Workspace {
        val now = Instant.parse("2026-06-12T00:00:00Z")
        return Workspace(
            id = WorkspaceId.random(),
            name = "workspace",
            repoUrl = null,
            branch = null,
            podName = "agent-runner",
            pvcName = "workspace-pvc",
            gatewayEndpoint = "http://runner:8090",
            status = WorkspaceStatus.READY,
            createdAt = now,
            updatedAt = now,
            currentRunnerSetupId = AgentSetupId("workspace-current"),
            currentRunnerSetupVersion = AgentSetupVersion(1),
        )
    }

    private fun session(workspace: Workspace): WorkspaceAgentSession {
        val now = Instant.parse("2026-06-12T00:00:00Z")
        return WorkspaceAgentSession(
            id = WorkspaceAgentSessionId.random(),
            workspaceId = workspace.id,
            kind = WorkspaceAgentKind.CODEX,
            gatewayAgentId = "agent-1",
            status = WorkspaceAgentSessionStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
            currentSetupId = AgentSetupId("session-current"),
            currentSetupVersion = AgentSetupVersion(3),
        )
    }

    private class RecordingAgentsApiTelemetry : AgentsApiTelemetry {
        val operations = mutableListOf<OperationTelemetry>()

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }
    }
}

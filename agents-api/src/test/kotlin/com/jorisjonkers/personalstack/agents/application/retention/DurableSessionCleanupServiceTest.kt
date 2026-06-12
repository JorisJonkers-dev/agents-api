package com.jorisjonkers.personalstack.agents.application.retention

import com.jorisjonkers.personalstack.agents.application.sessionbinding.PrepareRunnerInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerPreparationResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerProvisioningResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
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
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DurableSessionCleanupServiceTest {
    private val now = Instant.parse("2026-06-12T09:00:00Z")
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val gateway = mockk<AgentGatewayClient>(relaxed = true)
    private val binding = mockk<RunnerSessionBindingService>()
    private val runtime = runtimeProperties()
    private val sessionStatus = mockk<SessionStatusPublisher>(relaxed = true)
    private val service =
        DurableSessionCleanupService(
            workspaces = workspaces,
            sessions = sessions,
            gateway = gateway,
            binding = binding,
            runtime = runtime,
            sessionStatus = sessionStatus,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun `sweep marks expired stopped and failed sessions cleanup pending`() {
        val stopped = session(status = WorkspaceAgentSessionStatus.STOPPED)
        val failed = session(status = WorkspaceAgentSessionStatus.FAILED)
        every { sessions.findReadyForCleanup(now, runtime.durableSessionCleanupBatchSize) } returns
            listOf(stopped, failed)
        every { sessions.markCleanupRequested(stopped.id, now) } returns true
        every { sessions.markCleanupRequested(failed.id, now) } returns true
        every { sessions.findCleanupRequested(runtime.durableSessionCleanupBatchSize) } returns emptyList()

        val result = service.sweep()

        assertThat(result.markedPending).isEqualTo(2)
        assertThat(result.cleaned).isEqualTo(0)
        assertThat(result.failed).isEqualTo(0)
        verify { sessions.markCleanupRequested(stopped.id, now) }
        verify { sessions.markCleanupRequested(failed.id, now) }
    }

    @Test
    fun `sweep deletes pending session only after gateway cleanup succeeds`() {
        val workspace = workspace()
        val pending = session(workspaceId = workspace.id, cleanupRequestedAt = now.minusSeconds(60))
        every { sessions.findReadyForCleanup(now, runtime.durableSessionCleanupBatchSize) } returns emptyList()
        every { sessions.findCleanupRequested(runtime.durableSessionCleanupBatchSize) } returns listOf(pending)
        every { workspaces.findById(workspace.id) } returns workspace
        every { gateway.isReady(workspace) } returns true
        every { sessions.delete(pending.id) } returns true

        val result = service.sweep()

        assertThat(result.cleaned).isEqualTo(1)
        assertThat(result.failed).isEqualTo(0)
        verify { gateway.cleanupStableSession(workspace, pending.id) }
        verify { sessions.delete(pending.id) }
        verify { sessionStatus.publishRemove(pending.id) }
    }

    @Test
    fun `sweep leaves cleanup pending when gateway cleanup fails`() {
        val workspace = workspace()
        val pending = session(workspaceId = workspace.id, cleanupRequestedAt = now.minusSeconds(60))
        every { sessions.findReadyForCleanup(now, runtime.durableSessionCleanupBatchSize) } returns emptyList()
        every { sessions.findCleanupRequested(runtime.durableSessionCleanupBatchSize) } returns listOf(pending)
        every { workspaces.findById(workspace.id) } returns workspace
        every { gateway.isReady(workspace) } returns true
        every { gateway.cleanupStableSession(workspace, pending.id) } throws RuntimeException("gateway unavailable")

        val result = service.sweep()

        assertThat(result.cleaned).isEqualTo(0)
        assertThat(result.failed).isEqualTo(1)
        verify(exactly = 0) { sessions.delete(any()) }
    }

    @Test
    fun `sweep provisions runner before cleaning pending session when workspace is idle`() {
        val workspace =
            workspace(
                status = WorkspaceStatus.IDLE,
                podName = null,
                gatewayEndpoint = null,
            )
        val pending = session(workspaceId = workspace.id, cleanupRequestedAt = now.minusSeconds(60))
        val prepared =
            workspace.withPodInfo(
                podName = "agent-runner-new",
                pvcName = "workspace-pvc",
                gatewayEndpoint = "http://new:8090",
            )
        every { sessions.findReadyForCleanup(now, runtime.durableSessionCleanupBatchSize) } returns emptyList()
        every { sessions.findCleanupRequested(runtime.durableSessionCleanupBatchSize) } returns listOf(pending)
        every { workspaces.findById(workspace.id) } returns workspace
        every { gateway.isReady(workspace) } returns false
        every {
            binding.prepareRunner(PrepareRunnerInput(workspace.id, WorkspaceAgentKind.CLAUDE))
        } returns
            RunnerPreparationResult.Ready(
                workspace = prepared,
                setupId = prepared.currentRunnerSetupId,
                setupVersion = prepared.currentRunnerSetupVersion,
                provisioning =
                    RunnerProvisioningResult.Provisioned(
                        podName = "agent-runner-new",
                        pvcName = "workspace-pvc",
                        gatewayEndpoint = "http://new:8090",
                    ),
            )
        every { sessions.delete(pending.id) } returns true

        val result = service.sweep()

        assertThat(result.cleaned).isEqualTo(1)
        verify { binding.prepareRunner(PrepareRunnerInput(workspace.id, WorkspaceAgentKind.CLAUDE)) }
        verify { gateway.cleanupStableSession(prepared, pending.id) }
        verify { sessions.delete(pending.id) }
    }

    @Test
    fun `sweep leaves cleanup pending when session has pending setup`() {
        val workspace = workspace()
        val pending =
            session(workspaceId = workspace.id, cleanupRequestedAt = now.minusSeconds(60))
                .copy(
                    pendingSetupId = AgentSetupId("gpu"),
                    pendingSetupVersion = AgentSetupVersion(2),
                )
        every { sessions.findReadyForCleanup(now, runtime.durableSessionCleanupBatchSize) } returns emptyList()
        every { sessions.findCleanupRequested(runtime.durableSessionCleanupBatchSize) } returns listOf(pending)

        val result = service.sweep()

        assertThat(result.cleaned).isEqualTo(0)
        assertThat(result.failed).isEqualTo(1)
        verify(exactly = 0) { workspaces.findById(any()) }
        verify(exactly = 0) { gateway.cleanupStableSession(any(), any()) }
    }

    @Test
    fun `sweep does not mount runner while workspace setup transition is pending`() {
        val workspace =
            workspace(status = WorkspaceStatus.IDLE)
                .copy(
                    pendingRunnerSetupId = AgentSetupId("gpu"),
                    pendingRunnerSetupVersion = AgentSetupVersion(2),
                )
        val pending = session(workspaceId = workspace.id, cleanupRequestedAt = now.minusSeconds(60))
        every { sessions.findReadyForCleanup(now, runtime.durableSessionCleanupBatchSize) } returns emptyList()
        every { sessions.findCleanupRequested(runtime.durableSessionCleanupBatchSize) } returns listOf(pending)
        every { workspaces.findById(workspace.id) } returns workspace

        val result = service.sweep()

        assertThat(result.cleaned).isEqualTo(0)
        assertThat(result.failed).isEqualTo(1)
        verify(exactly = 0) { binding.prepareRunner(any()) }
        verify(exactly = 0) { gateway.cleanupStableSession(any(), any()) }
    }

    @Test
    fun `sweep does not publish remove when row delete loses the race`() {
        val workspace = workspace()
        val pending = session(workspaceId = workspace.id, cleanupRequestedAt = now.minusSeconds(60))
        every { sessions.findReadyForCleanup(now, runtime.durableSessionCleanupBatchSize) } returns emptyList()
        every { sessions.findCleanupRequested(runtime.durableSessionCleanupBatchSize) } returns listOf(pending)
        every { workspaces.findById(workspace.id) } returns workspace
        every { gateway.isReady(workspace) } returns true
        every { sessions.delete(pending.id) } returns false

        val result = service.sweep()

        assertThat(result.cleaned).isEqualTo(0)
        assertThat(result.failed).isEqualTo(1)
        verify(exactly = 0) { sessionStatus.publishRemove(any()) }
    }

    private fun workspace(
        status: WorkspaceStatus = WorkspaceStatus.READY,
        podName: String? = "agent-runner",
        gatewayEndpoint: String? = "http://x:8090",
    ) = Workspace(
        id = WorkspaceId.random(),
        name = "workspace",
        repoUrl = null,
        branch = null,
        podName = podName,
        pvcName = "workspace-pvc",
        gatewayEndpoint = gatewayEndpoint,
        status = status,
        createdAt = now.minusSeconds(3600),
        updatedAt = now.minusSeconds(60),
    )

    private fun session(
        workspaceId: WorkspaceId = WorkspaceId.random(),
        status: WorkspaceAgentSessionStatus = WorkspaceAgentSessionStatus.STOPPED,
        cleanupRequestedAt: Instant? = null,
    ) = WorkspaceAgentSession(
        id = WorkspaceAgentSessionId.random(),
        workspaceId = workspaceId,
        kind = WorkspaceAgentKind.CLAUDE,
        gatewayAgentId = null,
        status = status,
        createdAt = now.minusSeconds(3600),
        updatedAt = now.minusSeconds(60),
        epoch = 2,
        generation = 4,
        retainedUntil = now.minusSeconds(1),
        cleanupRequestedAt = cleanupRequestedAt,
    )

    private fun runtimeProperties() =
        AgentRuntimeProperties(
            namespace = "agents-system",
            image = "ghcr.io/example/agent-runner:latest",
            serviceAccount = "agent-runner",
            claudeCredentialsPvc = "claude-credentials",
            codexCredentialsPvc = "codex-credentials",
            githubDeployKeySecret = "agents-github-deploy-key",
            durableSessionRetentionSeconds = 3_600,
            durableSessionCleanupBatchSize = 3,
        )
}

package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
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
import org.springframework.web.client.ResourceAccessException
import java.time.Instant

class RunnerSessionBindingServiceTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val gateway = mockk<AgentGatewayClient>()
    private val orchestrator = mockk<AgentRunnerOrchestrator>()
    private val sessionStatus = mockk<SessionStatusPublisher>(relaxed = true)
    private val binder =
        RunnerSessionBinder(
            workspaces = workspaces,
            sessions = sessions,
            gateway = gateway,
            orchestrator = orchestrator,
            tx = RunnerSessionBindingTransactions(sessions, sessionStatus),
            sessionStatus = sessionStatus,
            backoffInitialMs = 0,
        )

    @Test
    fun `start creates epoch 1 generation and binds spawned stable gateway session`() {
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        val created = slot<WorkspaceAgentSession>()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(ws) } returns true
        every { sessions.save(capture(created)) } answers { created.captured }
        every {
            gateway.spawnAgent(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = sessionId,
                epoch = 1,
                continuation = null,
            )
        } returns gatewayAgent("abc12345", epoch = 1)
        every {
            sessions.bindIfGeneration(
                id = sessionId,
                expectedGeneration = 1,
                gatewayAgentId = "abc12345",
                cliSessionId = "native-1",
                now = any(),
            )
        } returns true
        every { sessions.findById(sessionId) } answers
            { created.captured.bindGatewayAgent("abc12345", "native-1") }

        val result =
            binder.start(
                StartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.CLAUDE,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        assertThat(created.captured.epoch).isEqualTo(1)
        assertThat(created.captured.generation).isEqualTo(1)
        verify(exactly = 0) { orchestrator.provision(any()) }
    }

    @Test
    fun `ensureBound rebinds stale idle binding with bumped epoch and generation`() {
        val ws = workspace(status = WorkspaceStatus.IDLE, gatewayEndpoint = null)
        val session =
            session(ws.id, gatewayAgentId = "stale-agent")
                .copy(epoch = 4, generation = 2)
        val repointed = ws.withPodInfo("agent-runner-fresh001", "workspace-abcdef01", "http://fresh:8090")
        every { sessions.findById(session.id) } returns session andThen
            session.beginGeneration(nextEpoch = 5).bindGatewayAgent("fresh")
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(any()) } returnsMany listOf(false, false, true)
        every {
            sessions.beginGeneration(
                id = session.id,
                expectedGeneration = 2,
                nextEpoch = 5,
                now = any(),
            )
        } returns true
        every { orchestrator.scaleDown(ws) } returns Unit
        every { orchestrator.provision(ws) } returns
            AgentRunnerOrchestrator.RunnerHandle(
                podName = "agent-runner-fresh001",
                pvcName = "workspace-abcdef01",
                gatewayEndpoint = "http://fresh:8090",
            )
        every { workspaces.save(any()) } returns repointed
        every {
            gateway.spawnAgent(
                workspace = repointed,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = session.id,
                epoch = 5,
                continuation = AgentGatewayClient.ContinuationMetadata(reason = "rebind", previousEpoch = 4),
            )
        } returns gatewayAgent("fresh", epoch = 5)
        every {
            sessions.bindIfGeneration(
                id = session.id,
                expectedGeneration = 3,
                gatewayAgentId = "fresh",
                cliSessionId = "native-1",
                now = any(),
            )
        } returns true

        val result =
            binder.ensureBound(
                EnsureRunnerSessionBoundInput(
                    sessionId = session.id,
                    workspaceId = ws.id,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        verify { sessions.beginGeneration(session.id, 2, 5, any()) }
        verify { gateway.spawnAgent(repointed, WorkspaceAgentKind.CLAUDE, null, session.id, 5, any()) }
    }

    @Test
    fun `start retries transient spawn transport failures before binding`() {
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        val created = slot<WorkspaceAgentSession>()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(ws) } returns true
        every { sessions.save(capture(created)) } answers { created.captured }
        every {
            gateway.spawnAgent(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = sessionId,
                epoch = 1,
                continuation = null,
            )
        }.throws(ResourceAccessException("Connection refused"))
            .andThenThrows(ResourceAccessException("Connection refused"))
            .andThen(gatewayAgent("abc12345", epoch = 1))
        every {
            sessions.bindIfGeneration(
                id = sessionId,
                expectedGeneration = 1,
                gatewayAgentId = "abc12345",
                cliSessionId = "native-1",
                now = any(),
            )
        } returns true
        every { sessions.findById(sessionId) } answers
            { created.captured.bindGatewayAgent("abc12345", "native-1") }

        binder.start(
            StartRunnerSessionBindingInput(
                workspaceId = ws.id,
                sessionId = sessionId,
                kind = WorkspaceAgentKind.CLAUDE,
            ),
        )

        verify(exactly = RunnerSessionBinder.MAX_SPAWN_ATTEMPTS) {
            gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE, null, sessionId, 1, null)
        }
    }

    @Test
    fun `restart force provisions fresh runner and spawns with continuation metadata`() {
        val ws = workspace()
        val session =
            session(ws.id, gatewayAgentId = "old-agent")
                .copy(epoch = 2, generation = 6)
        val repointed = ws.withPodInfo("agent-runner-fresh001", "workspace-abcdef01", "http://fresh:8090")
        every { sessions.findById(session.id) } returns session andThen
            session.beginGeneration(nextEpoch = 3).bindGatewayAgent("fresh")
        every { workspaces.findById(ws.id) } returns ws
        every {
            sessions.beginGeneration(
                id = session.id,
                expectedGeneration = 6,
                nextEpoch = 3,
                now = any(),
            )
        } returns true
        every { orchestrator.scaleDown(ws) } returns Unit
        every { orchestrator.provision(ws) } returns
            AgentRunnerOrchestrator.RunnerHandle(
                podName = "agent-runner-fresh001",
                pvcName = "workspace-abcdef01",
                gatewayEndpoint = "http://fresh:8090",
            )
        every { workspaces.save(any()) } returns repointed
        every { gateway.isReady(repointed) } returns true
        every {
            gateway.spawnAgent(
                workspace = repointed,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = session.id,
                epoch = 3,
                continuation = AgentGatewayClient.ContinuationMetadata(reason = "restart", previousEpoch = 2),
            )
        } returns gatewayAgent("fresh", epoch = 3)
        every {
            sessions.bindIfGeneration(
                id = session.id,
                expectedGeneration = 7,
                gatewayAgentId = "fresh",
                cliSessionId = "native-1",
                now = any(),
            )
        } returns true

        val result =
            binder.restart(
                RestartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    expectedGeneration = 6,
                    reason = "restart",
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        verify(exactly = 1) { orchestrator.scaleDown(ws) }
        verify(exactly = 1) { orchestrator.provision(ws) }
        verify { gateway.spawnAgent(repointed, WorkspaceAgentKind.CLAUDE, null, session.id, 3, any()) }
    }

    @Test
    fun `beginGeneration suppresses status publication when generation CAS fails`() {
        val session = session(WorkspaceId.random(), gatewayAgentId = "old-agent").copy(epoch = 2, generation = 6)
        val starting = session.beginGeneration(nextEpoch = 3)
        every {
            sessions.beginGeneration(
                id = session.id,
                expectedGeneration = 6,
                nextEpoch = 3,
                now = any(),
            )
        } returns false

        val result = RunnerSessionBindingTransactions(sessions, sessionStatus).beginGeneration(session, starting)

        assertThat(result).isFalse
        verify(exactly = 0) { sessionStatus.publishStatus(any<WorkspaceAgentSession>(), any()) }
    }

    private fun gatewayAgent(
        id: String,
        epoch: Long,
    ) = AgentGatewayClient.GatewayAgent(
        id = id,
        kind = WorkspaceAgentKind.CLAUDE,
        cwd = "/workspace",
        cliSessionId = "native-1",
        stableSessionId = "stable",
        epoch = epoch,
    )

    private fun workspace(
        status: WorkspaceStatus = WorkspaceStatus.READY,
        gatewayEndpoint: String? = "http://x:8090",
    ) = Workspace(
        id = WorkspaceId.random(),
        name = "demo",
        repoUrl = null,
        branch = null,
        podName = "agent-runner-abcdef01",
        pvcName = "workspace-abcdef01",
        gatewayEndpoint = gatewayEndpoint,
        status = status,
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
        cliSessionId = "native-old",
    )
}

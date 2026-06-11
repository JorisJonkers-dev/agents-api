package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
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
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.ResourceAccessException
import java.time.Instant

class StartAgentSessionCommandHandlerTest {
    private val workspaces = mockk<WorkspaceRepository>()

    private val sessions = mockk<WorkspaceAgentSessionRepository>(relaxed = true)
    private val gateway = mockk<AgentGatewayClient>()
    private val orchestrator = mockk<AgentRunnerOrchestrator>()

    /** `backoffInitialMs = 0` so the retry-exhaustion test doesn't burn 6 s. */
    private val handler =
        StartAgentSessionCommandHandler(workspaces, sessions, gateway, orchestrator, backoffInitialMs = 0)

    @Test
    fun `handle spawns gateway agent and persists binding when runner is ready`() {
        val ws = workspace()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(ws) } returns true
        val saved = mutableListOf<WorkspaceAgentSession>()
        every { sessions.save(capture(saved)) } answers { firstArg() }
        every { gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE) } returns
            AgentGatewayClient.GatewayAgent(id = "abc12345", kind = WorkspaceAgentKind.CLAUDE, cwd = "/workspace")

        handler.handle(
            StartAgentSessionCommand(
                sessionId = WorkspaceAgentSessionId.random(),
                workspaceId = ws.id,
                kind = WorkspaceAgentKind.CLAUDE,
            ),
        )

        verify(exactly = 2) { sessions.save(any()) }
        assertThat(saved.first().status).isEqualTo(WorkspaceAgentSessionStatus.STARTING)
        assertThat(saved.last().gatewayAgentId).isEqualTo("abc12345")
        assertThat(saved.last().status).isEqualTo(WorkspaceAgentSessionStatus.RUNNING)
    }

    @Test
    fun `handle starts fresh even when prior sessions exist for the same workspace and kind`() {
        val ws = workspace()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(ws) } returns true
        every { sessions.save(any()) } answers { firstArg() }
        every { sessions.findAllByWorkspaceId(ws.id) } returns
            listOf(
                WorkspaceAgentSession(
                    id = WorkspaceAgentSessionId.random(),
                    workspaceId = ws.id,
                    kind = WorkspaceAgentKind.CLAUDE,
                    gatewayAgentId = "old-agent",
                    status = WorkspaceAgentSessionStatus.RUNNING,
                    createdAt = Instant.parse("2026-05-19T10:00:00Z"),
                    updatedAt = Instant.parse("2026-05-19T10:00:00Z"),
                    cliSessionId = "old-native-session",
                ),
            )
        every { gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE) } returns
            AgentGatewayClient.GatewayAgent(id = "fresh", kind = WorkspaceAgentKind.CLAUDE, cwd = "/workspace")

        handler.handle(
            StartAgentSessionCommand(
                sessionId = WorkspaceAgentSessionId.random(),
                workspaceId = ws.id,
                kind = WorkspaceAgentKind.CLAUDE,
            ),
        )

        verify(exactly = 0) { sessions.findAllByWorkspaceId(any()) }
        verify(exactly = 1) { gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE) }
    }

    @Test
    fun `handle raises NoSuchElementException when workspace does not exist`() {
        val missingId = WorkspaceId.random()
        every { workspaces.findById(missingId) } returns null

        // 404 via GlobalExceptionHandler — previously this surfaced as a
        // 500 IllegalStateException, which read as a backend bug instead
        // of "the workspace id is wrong".
        val ex =
            assertThrows<NoSuchElementException> {
                handler.handle(
                    StartAgentSessionCommand(
                        sessionId = WorkspaceAgentSessionId.random(),
                        workspaceId = missingId,
                        kind = WorkspaceAgentKind.CODEX,
                    ),
                )
            }
        assertThat(ex.message).contains(missingId.value.toString())
    }

    @Test
    fun `handle does not touch the orchestrator when the runner is already ready`() {
        // A healthy runner must never be destroyed: the self-heal path
        // is gated entirely behind a failing /healthz, so a Ready
        // runner provisions nothing and spawns straight away.
        val ws = workspace()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(ws) } returns true
        every { sessions.save(any()) } answers { firstArg() }
        every { gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE) } returns
            AgentGatewayClient.GatewayAgent(id = "ok", kind = WorkspaceAgentKind.CLAUDE, cwd = "/workspace")

        handler.handle(
            StartAgentSessionCommand(
                sessionId = WorkspaceAgentSessionId.random(),
                workspaceId = ws.id,
                kind = WorkspaceAgentKind.CLAUDE,
            ),
        )

        verify(exactly = 0) { orchestrator.scaleDown(any()) }
        verify(exactly = 0) { orchestrator.destroy(any()) }
        verify(exactly = 0) { orchestrator.provision(any()) }
    }

    @Test
    fun `handle re-provisions a fresh Pod when the runner is missing or crash-looping`() {
        // Regression: a workspace whose Pod was evicted / built against
        // an older image answers nothing on /healthz forever. Instead
        // of a permanent 503, the runner is re-provisioned (scaleDown +
        // provision lands a fresh Pod on the current image), the PVC is
        // preserved, the workspace is re-pointed at the new endpoint,
        // and the fresh Pod passes /healthz on the next gate.
        val ws = workspace()
        every { workspaces.findById(ws.id) } returns ws
        // First gate fails (stale/missing Pod); after re-provision the
        // fresh Pod answers.
        every { gateway.isReady(any()) } returnsMany listOf(false, true)
        every { orchestrator.scaleDown(ws) } returns Unit
        every { orchestrator.provision(ws) } returns
            AgentRunnerOrchestrator.RunnerHandle(
                podName = "agent-runner-fresh001",
                pvcName = "workspace-abcdef01",
                gatewayEndpoint = "http://fresh:8090",
            )
        val saved = mutableListOf<Workspace>()
        every { workspaces.save(capture(saved)) } answers { firstArg() }
        every { sessions.save(any()) } answers { firstArg() }
        every { gateway.spawnAgent(any(), WorkspaceAgentKind.CLAUDE) } returns
            AgentGatewayClient.GatewayAgent(id = "ok", kind = WorkspaceAgentKind.CLAUDE, cwd = "/workspace")

        handler.handle(
            StartAgentSessionCommand(
                sessionId = WorkspaceAgentSessionId.random(),
                workspaceId = ws.id,
                kind = WorkspaceAgentKind.CLAUDE,
            ),
        )

        verify(exactly = 1) { orchestrator.scaleDown(ws) }
        verify(exactly = 0) { orchestrator.destroy(any()) }
        verify(exactly = 1) { orchestrator.provision(ws) }
        // The re-pointed workspace is persisted and spawned against.
        assertThat(saved.single().podName).isEqualTo("agent-runner-fresh001")
        assertThat(saved.single().gatewayEndpoint).isEqualTo("http://fresh:8090")
        verify { gateway.spawnAgent(match { it.gatewayEndpoint == "http://fresh:8090" }, WorkspaceAgentKind.CLAUDE) }
    }

    @Test
    fun `handle surfaces ReprovisionFailed 503 when provision throws`() {
        // If re-provision itself can't land a Pod, there is nothing to
        // spawn against — surface a typed 503 carrying the provision
        // failure as the cause, and never create a session row.
        val ws = workspace()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(ws) } returns false
        every { orchestrator.scaleDown(ws) } returns Unit
        every { orchestrator.provision(ws) } throws IllegalStateException("k8s apply rejected")

        val ex =
            assertThrows<AgentRunnerUnavailableException> {
                handler.handle(
                    StartAgentSessionCommand(
                        sessionId = WorkspaceAgentSessionId.random(),
                        workspaceId = ws.id,
                        kind = WorkspaceAgentKind.CLAUDE,
                    ),
                )
            }
        assertThat(ex.runnerStatus).isEqualTo("ReprovisionFailed")
        assertThat(ex.cause).isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { sessions.save(any()) }
        verify(exactly = 0) { gateway.spawnAgent(any(), any()) }
    }

    @Test
    fun `handle surfaces NotReady 503 when the fresh Pod never passes healthz within the budget`() {
        // Re-provision succeeded but the fresh Pod stays NotReady for
        // the whole retry budget — surface a NotReady 503 so the client
        // backs off, without spawning against a Pod that can't answer.
        val ws = workspace()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(any()) } returns false
        every { orchestrator.scaleDown(ws) } returns Unit
        every { orchestrator.provision(ws) } returns
            AgentRunnerOrchestrator.RunnerHandle(
                podName = "agent-runner-fresh001",
                pvcName = "workspace-abcdef01",
                gatewayEndpoint = "http://fresh:8090",
            )
        every { workspaces.save(any()) } answers { firstArg() }

        val ex =
            assertThrows<AgentRunnerUnavailableException> {
                handler.handle(
                    StartAgentSessionCommand(
                        sessionId = WorkspaceAgentSessionId.random(),
                        workspaceId = ws.id,
                        kind = WorkspaceAgentKind.CLAUDE,
                    ),
                )
            }
        assertThat(ex.runnerStatus).isEqualTo("NotReady")
        assertThat(ex.retryAfterSeconds).isEqualTo(AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS)
        verify(exactly = 1) { orchestrator.provision(ws) }
        verify(exactly = 0) { sessions.save(any()) }
        verify(exactly = 0) { gateway.spawnAgent(any(), any()) }
    }

    @Test
    fun `handle retries spawn on ResourceAccessException and succeeds when the runner comes up mid-attempt`() {
        val ws = workspace()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(ws) } returns true
        every { sessions.save(any()) } answers { firstArg() }
        // Fail twice with `Connection refused`, then succeed on the
        // third attempt — the readiness gate races with the
        // runner's HTTP listener binding, so 1-2 attempts of grace
        // is realistic in CI + prod.
        every { gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE) }
            .throws(ResourceAccessException("Connection refused"))
            .andThenThrows(ResourceAccessException("Connection refused"))
            .andThen(
                AgentGatewayClient.GatewayAgent(id = "ok", kind = WorkspaceAgentKind.CLAUDE, cwd = "/workspace"),
            )

        handler.handle(
            StartAgentSessionCommand(
                sessionId = WorkspaceAgentSessionId.random(),
                workspaceId = ws.id,
                kind = WorkspaceAgentKind.CLAUDE,
            ),
        )

        verify(exactly = 3) { gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE) }
        verify(exactly = 2) { sessions.save(any()) }
    }

    @Test
    fun `handle surfaces AgentRunnerUnavailable when every spawn retry hits ResourceAccessException`() {
        // 3 attempts × ConnectionRefused → typed 503 with the original
        // RestClient exception attached as the cause for log
        // correlation. The session row is the STARTING placeholder
        // that was inserted before the spawn; cleanup is the caller's
        // responsibility (no rollback here because @Transactional
        // gives us that for free on the rethrown exception).
        val ws = workspace()
        every { workspaces.findById(ws.id) } returns ws
        every { gateway.isReady(ws) } returns true
        every { sessions.save(any()) } answers { firstArg() }
        every { gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE) } throws
            ResourceAccessException("Connection refused")

        val ex =
            assertThrows<AgentRunnerUnavailableException> {
                handler.handle(
                    StartAgentSessionCommand(
                        sessionId = WorkspaceAgentSessionId.random(),
                        workspaceId = ws.id,
                        kind = WorkspaceAgentKind.CLAUDE,
                    ),
                )
            }
        assertThat(ex.runnerStatus).isEqualTo("ConnectionRefused")
        assertThat(ex.cause).isInstanceOf(ResourceAccessException::class.java)
        verify(exactly = StartAgentSessionCommandHandler.MAX_SPAWN_ATTEMPTS) {
            gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE)
        }
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

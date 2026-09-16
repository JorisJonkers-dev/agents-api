package com.jorisjonkers.personalstack.agents.shell

import com.jorisjonkers.personalstack.agents.application.sessionbinding.InContainerSessionBindingService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.StartRunnerSessionBindingInput
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.application.workspace.WorkspaceDirectoryService
import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.InContainerAgentGatewayClient
import com.jorisjonkers.personalstack.agents.infrastructure.process.ProcessRunner
import com.jorisjonkers.personalstack.agents.infrastructure.process.RunAsAgentCommandRunner
import com.jorisjonkers.personalstack.agents.infrastructure.shell.InContainerTmuxClient
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellAttachOperations
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellSessionRegistry
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Exercises create, Attach and Stop for a Shell Agent Session against a
 * **real tmux binary** — nothing in this estate has tested tmux or a
 * live Attach against a real process before (agent-gateway's own
 * `build.gradle.kts` claims system-test coverage that does not exist).
 *
 * This proves: the ported tmux argv shapes are accepted by a real tmux
 * server, a real `/bin/bash -l` pane actually runs as a child of that
 * server, keystrokes sent via `send-keys` reach it, its output is
 * genuinely tailed off the pipe-pane log by [com.jorisjonkers.personalstack.agents.infrastructure.shell.LogTailer],
 * and `kill-session` really ends the process.
 *
 * This does NOT prove: behaviour inside the real agents-api container.
 * `run-as-agent` is replaced by a plain passthrough script (`exec
 * "$@"`), so no uid switch happens and this never runs as `agent` —
 * ADR 0003's privilege boundary is exercised by `run-as-agent.c` and
 * its own build, not here. It also does not open a real browser
 * WebSocket: it drives [ShellAttachOperations] directly — the same
 * calls [com.jorisjonkers.personalstack.agents.infrastructure.ws.LocalShellAttachSupport]
 * makes from the WebSocket handler — so the JSON envelope and the
 * transport itself are outside this test's scope, but the tmux side of
 * every Attach operation (snapshot, tail, input, resize) is real.
 *
 * tmux is not guaranteed on the CI runner, so this test is skipped —
 * not failed — when it is absent (`Assumptions.assumeTrue`).
 */
@Tag("integration")
class ShellAgentSessionIntegrationTest {
    @TempDir
    lateinit var workspacesRoot: Path

    private lateinit var socketName: String
    private lateinit var props: AgentRuntimeProperties
    private lateinit var registry: ShellSessionRegistry
    private lateinit var tmuxClient: InContainerTmuxClient
    private lateinit var attachOps: ShellAttachOperations
    private lateinit var gateway: InContainerAgentGatewayClient
    private lateinit var binding: InContainerSessionBindingService
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var sessions: AgentSessionRepository
    private lateinit var workspace: Workspace

    @BeforeEach
    fun setUp() {
        assumeTrue(tmuxOnPath(), "tmux is not installed on this runner — skipping the real-tmux integration test")

        socketName = "agents-api-it-${System.nanoTime()}"
        val runAsAgentPassthrough = passthroughScript(workspacesRoot)
        props =
            AgentRuntimeProperties(
                namespace = "agents-system",
                image = "unused",
                serviceAccount = "unused",
                claudeCredentialsPvc = "unused",
                codexCredentialsPvc = "unused",
                githubDeployKeySecret = "unused",
                workspacesRoot = workspacesRoot.toString(),
                runAsAgentPath = runAsAgentPassthrough.toString(),
                shellTmuxSocketName = socketName,
            )

        val commands = RunAsAgentCommandRunner(ProcessRunner(), props)
        registry = ShellSessionRegistry()
        tmuxClient = InContainerTmuxClient(commands, props)
        attachOps = ShellAttachOperations(tmuxClient, registry)
        val directories = WorkspaceDirectoryService(props, commands)
        gateway = InContainerAgentGatewayClient(tmuxClient, directories, registry, commands, attachOps)

        val workspaceId = WorkspaceId.random()
        workspace =
            Workspace(
                id = workspaceId,
                name = "integration-test",
                repoUrl = null,
                branch = null,
                podName = null,
                pvcName = null,
                gatewayEndpoint = null,
                status = WorkspaceStatus.PREPARING,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                kind = WorkspaceKind.SCRATCH,
            )
        workspaces = SingleWorkspaceRepository(workspace)
        sessions = InMemoryAgentSessionRepository()
        val sessionStatus = mockk<SessionStatusPublisher>(relaxed = true)
        binding = InContainerSessionBindingService(workspaces, sessions, gateway, sessionStatus, directories)
    }

    @AfterEach
    fun tearDown() {
        if (this::socketName.isInitialized) {
            ProcessBuilder("tmux", "-L", socketName, "kill-server")
                .redirectErrorStream(true)
                .start()
                .waitFor(PROCESS_WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun createAttachAndStopAShellAgentSessionAgainstARealTmuxServer() {
        // Create: starts a real tmux session running /bin/bash -l, owned by
        // this process (run-as-agent is a passthrough here — see class doc).
        val bound =
            binding.start(
                StartRunnerSessionBindingInput(
                    workspaceId = workspace.id,
                    sessionId = AgentSessionId.random(),
                    kind = WorkspaceAgentKind.SHELL,
                ),
            ) as RunnerSessionBindingResult.Bound
        val gatewayAgentId = bound.gatewayAgent.id

        // No Kubernetes API call was needed to get here (criterion 1): the
        // whole path above was local processes and a directory.
        assertThat(Files.isDirectory(workspacesRoot.resolve(workspace.id.toString()))).isTrue()

        // Attach: a real snapshot + a real tailer against the real pipe-pane
        // log, exactly as LocalShellAttachSupport drives them.
        val outputFrames = CopyOnWriteArrayList<String>()
        val tailer = attachOps.startTailer(workspace, gatewayAgentId) { outputFrames += it }
        try {
            attachOps.sendInput(workspace, gatewayAgentId, "echo shell-attach-marker-42", true)

            awaitUntil { outputFrames.joinToString("").contains("shell-attach-marker-42") }

            // Resize doesn't error against a real pane.
            attachOps.resize(workspace, gatewayAgentId, RESIZE_COLS, RESIZE_ROWS)
        } finally {
            tailer.close()
        }

        // Stop: the real tmux session and its /bin/bash -l process are gone.
        gateway.stopAgent(workspace, gatewayAgentId)

        assertThat(tmuxClient.sessionExists("agent-${workspace.id.short()}-$gatewayAgentId")).isFalse()
        assertThat(sessions.findById(bound.session.id)).isNotNull()
    }

    @Test
    fun aBrowserReloadReattachesToTheSameStillRunningProcess() {
        val bound =
            binding.start(
                StartRunnerSessionBindingInput(
                    workspaceId = workspace.id,
                    sessionId = AgentSessionId.random(),
                    kind = WorkspaceAgentKind.SHELL,
                ),
            ) as RunnerSessionBindingResult.Bound
        val gatewayAgentId = bound.gatewayAgent.id
        attachOps.sendInput(workspace, gatewayAgentId, "echo before-reload", true)
        awaitUntil { attachOps.capture(workspace, gatewayAgentId).contains("before-reload") }

        // "Reload" = a second Attach against the same gatewayAgentId, the way
        // ensureBound's fast path leaves it after the first browser tab drops.
        val secondSnapshot = attachOps.snapshot(workspace, gatewayAgentId)

        assertThat(secondSnapshot).contains("before-reload")
        gateway.stopAgent(workspace, gatewayAgentId)
    }

    private fun tmuxOnPath(): Boolean =
        runCatching {
            ProcessBuilder(
                "tmux",
                "-V",
            ).redirectErrorStream(true).start().waitFor(PROCESS_WAIT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)

    private fun passthroughScript(dir: Path): Path {
        val script = dir.resolve("run-as-agent-passthrough.sh")
        Files.writeString(script, "#!/bin/sh\nexec \"\$@\"\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    // No dedicated await library in this source set; the tailer polls every
    // 40ms and pipe-pane writes are near-instant, so a short bounded poll is
    // plenty and keeps this test dependency-free.
    private fun awaitUntil(
        timeoutMs: Long = 10_000,
        conditionMet: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!conditionMet()) {
            check(System.nanoTime() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        private const val PROCESS_WAIT_SECONDS = 5L
        private const val RESIZE_COLS = 100
        private const val RESIZE_ROWS = 30

        const val POLL_INTERVAL_MS = 50L
    }
}

// Hand-rolled fakes rather than mocks: mockk's proxy for an interface method
// taking an inline value-class parameter (AgentSessionId/WorkspaceId) hands
// answer blocks the unboxed underlying UUID, not the wrapper — firstArg<T>()
// then throws a ClassCastException. A plain in-memory implementation sidesteps
// that entirely and is barely more code.
private class SingleWorkspaceRepository(
    private val workspace: Workspace,
) : WorkspaceRepository {
    override fun save(workspace: Workspace) = workspace

    override fun findById(id: WorkspaceId): Workspace? = workspace.takeIf { it.id == id }

    override fun findAllByStatusNot(status: WorkspaceStatus) = listOfNotNull(workspace.takeIf { it.status != status })

    override fun beginRunnerSetupOperation(request: WorkspaceRepository.RunnerSetupOperationRequest) =
        error("not used by this test")

    override fun completeRunnerSetupOperation(
        id: WorkspaceId,
        expectedGeneration: Long,
        now: Instant,
    ) = error("not used by this test")

    override fun failRunnerSetupOperation(
        id: WorkspaceId,
        expectedGeneration: Long,
        now: Instant,
    ) = error("not used by this test")

    override fun releaseStaleRunnerSetupOperation(
        id: WorkspaceId,
        olderThan: Instant,
        now: Instant,
    ) = error("not used by this test")

    override fun acquireBootLease(
        id: WorkspaceId,
        leaseId: UUID,
        now: Instant,
    ) = error("not used by this test")

    override fun completeBootLease(
        id: WorkspaceId,
        leaseId: UUID,
        now: Instant,
    ) = error("not used by this test")

    override fun failBootLease(
        id: WorkspaceId,
        leaseId: UUID,
        now: Instant,
    ) = error("not used by this test")

    override fun releaseStaleBootLease(
        id: WorkspaceId,
        olderThan: Instant,
        now: Instant,
    ) = error("not used by this test")

    override fun delete(id: WorkspaceId) = error("not used by this test")
}

private class InMemoryAgentSessionRepository : AgentSessionRepository {
    private val sessions = mutableMapOf<AgentSessionId, AgentSession>()

    override fun save(session: AgentSession): AgentSession {
        sessions[session.id] = session
        return session
    }

    override fun findById(id: AgentSessionId): AgentSession? = sessions[id]

    override fun findAllByWorkspaceId(workspaceId: WorkspaceId) =
        sessions.values.filter { it.workspaceId == workspaceId }

    override fun beginGeneration(
        id: AgentSessionId,
        expectedGeneration: Long,
        nextEpoch: Long,
        now: Instant,
    ) = error("not used by this test")

    override fun bindIfGeneration(
        id: AgentSessionId,
        expectedGeneration: Long,
        gatewayAgentId: String,
        cliSessionId: String?,
        now: Instant,
    ) = error("not used by this test")

    override fun clearGatewayBindingIfGeneration(
        id: AgentSessionId,
        expectedGeneration: Long,
        now: Instant,
    ) = error("not used by this test")

    override fun markLifecycleIfGeneration(update: AgentSessionRepository.LifecycleUpdate) =
        error("not used by this test")

    override fun findReadyForCleanup(
        now: Instant,
        limit: Int,
    ) = error("not used by this test")

    override fun markCleanupRequested(
        id: AgentSessionId,
        now: Instant,
    ) = error("not used by this test")

    override fun setPendingSetupIfCurrent(update: AgentSessionRepository.PendingSetupUpdate) =
        error("not used by this test")

    override fun promotePendingSetupIfCurrent(
        id: AgentSessionId,
        expectedPendingSetupId: AgentSetupId,
        expectedPendingSetupVersion: AgentSetupVersion,
        now: Instant,
    ) = error("not used by this test")

    override fun clearPendingSetupIfCurrent(
        id: AgentSessionId,
        expectedPendingSetupId: AgentSetupId,
        expectedPendingSetupVersion: AgentSetupVersion,
        now: Instant,
    ) = error("not used by this test")

    override fun findCleanupRequested(limit: Int) = error("not used by this test")

    override fun delete(id: AgentSessionId) = error("not used by this test")
}

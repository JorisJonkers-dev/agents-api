package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.application.workspace.WorkspaceDirectoryService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.infrastructure.process.RunAsAgentCommandRunner
import com.jorisjonkers.personalstack.agents.infrastructure.shell.InContainerTmuxClient
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellAttachOperations
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellSession
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellSessionRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The in-container [AgentGatewayClient]: a Shell Agent Session running
 * in tmux inside this container as `agent`, no runner Pod involved.
 * agent-gateway's tmux session management moves here for the Shell
 * tracer bullet (#62); Claude and Codex keep going through
 * [HttpAgentGatewayClient] to a runner Pod until they get their own
 * in-container support.
 *
 * Every method not needed by the tracer bullet throws
 * [UnsupportedOperationException] naming this scope, rather than
 * quietly returning a wrong answer — except [cleanupStableSession],
 * whose job (deleting durable transcript state) is a genuine no-op
 * here because Shell has no durable transcript store in this scope,
 * and [clone] (#63), needed by every in-container Workspace kind, not
 * only Shell ones. Attach-specific tmux operations (snapshot, resize,
 * tailer) live in [ShellAttachOperations], used directly by the
 * WebSocket bridge.
 */
@Component
class InContainerAgentGatewayClient(
    private val tmux: InContainerTmuxClient,
    private val directories: WorkspaceDirectoryService,
    private val registry: ShellSessionRegistry,
    private val commands: RunAsAgentCommandRunner,
    private val attach: ShellAttachOperations,
) : AgentGatewayClient {
    private val log = LoggerFactory.getLogger(InContainerAgentGatewayClient::class.java)

    override fun spawnAgent(request: AgentGatewayClient.SpawnAgentRequest): AgentGatewayClient.GatewayAgent {
        val command = commandFor(request.kind)
        val workspace = request.workspace
        val cwd = request.workspacePath ?: directories.ensureCreated(workspace.id).toString()
        val id = UUID.randomUUID().toString().substring(0, ID_PREVIEW_CHARS)
        val tmuxSessionName = "agent-${workspace.id.short()}-$id"
        val logFile = sessionLogFile(cwd, id)
        tmux.newSession(tmuxSessionName, command, cwd)
        tmux.startPipeToFile(tmuxSessionName, logFile)
        registry.put(
            ShellSession(
                gatewayAgentId = id,
                workspaceId = workspace.id,
                tmuxSessionName = tmuxSessionName,
                cwd = cwd,
                logFile = logFile,
                createdAt = Instant.now(),
            ),
        )
        log.info("spawned {} agent {} ({}) in {}", request.kind, id, tmuxSessionName, cwd)
        return AgentGatewayClient.GatewayAgent(id = id, kind = request.kind, cwd = cwd)
    }

    override fun stopAgent(
        workspace: Workspace,
        gatewayAgentId: String,
    ) {
        val session = registry.remove(workspace.id, gatewayAgentId) ?: return
        tmux.killSession(session.tmuxSessionName)
    }

    // Shell has no durable transcript store in this scope, so there is
    // nothing to clean up — a true no-op, not an unsupported operation.
    override fun cleanupStableSession(
        workspace: Workspace,
        stableSessionId: AgentSessionId,
    ) = Unit

    override fun sendInput(
        workspace: Workspace,
        gatewayAgentId: String,
        input: String,
        enter: Boolean,
    ) = attach.sendInput(workspace, gatewayAgentId, input, enter)

    override fun stageInput(
        workspace: Workspace,
        gatewayAgentId: String,
        content: String,
        name: String?,
    ): AgentGatewayClient.StagedInput = unsupported("stageInput")

    override fun capture(
        workspace: Workspace,
        gatewayAgentId: String,
    ): String = attach.capture(workspace, gatewayAgentId)

    // Idempotent, like WorkspaceDirectoryService.ensureCreated: a Repo-backed
    // Workspace is provisioned in both this container and a runner Pod during
    // the #67 transition (see WorkspaceRuntimeProvisioner), so a second call
    // for the same repo must be a no-op, not a failure.
    override fun clone(
        workspace: Workspace,
        repoUrl: String,
        branch: String?,
    ): String {
        val workspaceDir = directories.ensureCreated(workspace.id)
        val targetDir = workspaceDir.resolve(repoNameFrom(repoUrl))
        if (Files.isDirectory(targetDir)) {
            return targetDir.toString()
        }
        val argv =
            buildList {
                add("git")
                add("-c")
                add(CREDENTIAL_HELPER_CONFIG)
                add("-c")
                add(CREDENTIAL_USE_HTTP_PATH_CONFIG)
                add("clone")
                branch?.let {
                    add("--branch")
                    add(it)
                }
                add(repoUrl)
                add(targetDir.toString())
            }
        commands.run(argv, cwd = workspaceDir.toFile(), timeoutSeconds = CLONE_TIMEOUT_SECONDS)
        // Persist the same two settings into the clone's own .git/config, so a
        // later push or fetch by an Agent Session — which passes no `-c` flags
        // of its own — still resolves credentials through the socket.
        commands.run(listOf("git", "config", "credential.helper", "agents-api"), cwd = targetDir.toFile())
        commands.run(listOf("git", "config", "credential.useHttpPath", "true"), cwd = targetDir.toFile())
        return targetDir.toString()
    }

    // Matches the last-path-segment-minus-.git convention the runner Pod's
    // own entrypoint clones into (RunnerPodSpecBuilder's REPO_URL/REPO_URLS
    // comments), so the same repo lands under the same name in both places.
    private fun repoNameFrom(repoUrl: String): String {
        val tail = repoUrl.trim().substringAfterLast('/').substringAfterLast(':')
        return tail.removeSuffix(".git").ifBlank { "repo" }
    }

    override fun openPr(
        workspace: Workspace,
        repoDir: String,
        title: String,
        body: String,
        base: String,
    ): String = unsupported("openPr")

    // No network hop and no boot lease for a Shell Agent Session — once the
    // Workspace directory exists there is nothing further to wait on.
    override fun isReady(workspace: Workspace): Boolean = true

    override fun agentIdle(
        workspace: Workspace,
        gatewayAgentId: String,
    ): Duration? {
        val session = registry.find(workspace.id, gatewayAgentId) ?: return null
        return runCatching {
            val lastWrite = Files.getLastModifiedTime(session.logFile).toMillis()
            Duration.ofMillis((Instant.now().toEpochMilli() - lastWrite).coerceAtLeast(0L))
        }.getOrNull()
    }

    override fun startHeadlessJob(request: AgentGatewayClient.HeadlessJobRequest): AgentGatewayClient.HeadlessJob =
        unsupported("startHeadlessJob")

    override fun pollHeadlessJob(
        workspace: Workspace,
        headlessJobId: String,
    ): AgentGatewayClient.HeadlessJob = unsupported("pollHeadlessJob")

    private fun sessionLogFile(
        cwd: String,
        gatewayAgentId: String,
    ): Path {
        val dir = Path.of(cwd, SESSIONS_SUBDIR)
        commands.run(listOf("mkdir", "-p", dir.toString()))
        return dir.resolve("$gatewayAgentId.log")
    }

    private fun unsupported(operation: String): Nothing =
        throw UnsupportedOperationException(
            "$operation is not implemented for in-container Shell Agent Sessions " +
                "(tracer bullet #62 scope); implement it in a follow-up when this capability is needed.",
        )

    private companion object {
        const val ID_PREVIEW_CHARS = 8
        const val SESSIONS_SUBDIR = ".agent-sessions"

        // A clone runs over the network, unlike every other command this
        // client shells out through; the 30s default in RunAsAgentCommandRunner
        // is sized for local tmux/mkdir calls, not this.
        const val CLONE_TIMEOUT_SECONDS = 120L

        const val CREDENTIAL_HELPER_CONFIG = "credential.helper=agents-api"
        const val CREDENTIAL_USE_HTTP_PATH_CONFIG = "credential.useHttpPath=true"
    }
}

// What tmux runs for each Agent Kind. Bare `claude` and `codex` are the
// interactive TUIs: when the home volume holds no Agent Login yet, the CLI's
// own sign-in prompt is what the user completes from this terminal, which is
// the only way a login is ever created (ADR 0002). The headless forms --
// `claude -p`, `codex exec` -- belong to #66.
//
// `when` over the enum rather than a map: a new Agent Kind then fails the
// build here instead of failing a request at runtime.
private fun commandFor(kind: WorkspaceAgentKind): List<String> =
    when (kind) {
        WorkspaceAgentKind.SHELL -> listOf("/bin/bash", "-l")
        WorkspaceAgentKind.CLAUDE -> listOf("claude")
        WorkspaceAgentKind.CODEX -> listOf("codex")
    }

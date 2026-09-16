package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.GitCredentialSocketManager
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Re-opens every non-Destroyed Workspace's git-credential socket on
 * boot (#63) — a redeploy loses every listener along with the process
 * that held it. Skips a Workspace with no directory yet (never
 * provisioned, or the Pod-per-Repo-backed-Workspace path, which has no
 * `/workspaces/<id>` in this container) and, on a developer laptop
 * where `agent-runtime.workspaces-root` doesn't exist at all, does
 * nothing rather than failing startup.
 */
@Component
class GitCredentialSocketStartupInitializer(
    private val props: AgentRuntimeProperties,
    private val workspaces: WorkspaceRepository,
    private val directories: WorkspaceDirectoryService,
    private val gitCredentialSockets: GitCredentialSocketManager,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(GitCredentialSocketStartupInitializer::class.java)

    override fun run(args: ApplicationArguments) {
        val root = Path.of(props.workspacesRoot)
        if (!Files.isDirectory(root)) {
            log.info("workspaces root {} does not exist; skipping git-credential socket bootstrap", root)
            return
        }
        workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED).forEach { workspace ->
            if (Files.isDirectory(directories.directoryFor(workspace.id))) {
                runCatching { gitCredentialSockets.ensureStarted(workspace.id) }
                    .onFailure {
                        log.warn(
                            "failed to start git-credential socket for workspace {}",
                            workspace.id.value,
                            it,
                        )
                    }
            }
        }
    }
}

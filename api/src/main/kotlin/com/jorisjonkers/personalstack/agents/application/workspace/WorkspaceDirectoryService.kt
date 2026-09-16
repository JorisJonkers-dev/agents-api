package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.infrastructure.process.RunAsAgentCommandRunner
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A Workspace's directory on the workspaces volume: deterministic from
 * its id, so nothing needs to persist a path. `/workspaces` (the
 * configured root) is owned by `agent` at mode 0755 (ADR 0003), so the
 * `api`-owned JVM cannot create a subdirectory there directly — the
 * directory is created by `agent` itself via run-as-agent.
 */
@Component
class WorkspaceDirectoryService(
    private val props: AgentRuntimeProperties,
    private val commands: RunAsAgentCommandRunner,
) {
    fun directoryFor(workspaceId: WorkspaceId): Path = Paths.get(props.workspacesRoot, workspaceId.toString())

    /** Creates the Workspace's directory (idempotent) and returns its path. */
    fun ensureCreated(workspaceId: WorkspaceId): Path {
        val dir = directoryFor(workspaceId)
        commands.run(listOf("mkdir", "-p", dir.toString()))
        return dir
    }

    fun credentialSocketDirFor(workspaceId: WorkspaceId): Path =
        directoryFor(workspaceId).resolve(CREDENTIAL_SOCKET_DIR)

    /**
     * Creates `<workspaceDir>/.agents-api` (idempotent) and returns its
     * path. `<workspaceDir>` is `agent:agent` 0755 (ADR 0003), so the
     * `api`-owned JVM cannot create an entry in it directly — this
     * subdirectory is created by `agent` via run-as-agent, then chmod'd
     * `2703` (owner `agent` rwx, setgid, other `-wx`) so the JVM can
     * still bind/unlink its git-credential socket file there. The setgid
     * bit makes that socket file inherit group `agent` on creation even
     * though the JVM's own primary group is `api` — group ownership the
     * JVM has no privilege to set itself with an explicit chown/chgrp.
     */
    fun ensureCredentialSocketDirCreated(workspaceId: WorkspaceId): Path {
        ensureCreated(workspaceId)
        val dir = credentialSocketDirFor(workspaceId)
        commands.run(listOf("mkdir", "-p", dir.toString()))
        commands.run(listOf("chmod", CREDENTIAL_SOCKET_DIR_MODE, dir.toString()))
        return dir
    }

    private companion object {
        const val CREDENTIAL_SOCKET_DIR = ".agents-api"

        // setgid(2000) + owner rwx(700) + group ---(000) + other -wx(003).
        const val CREDENTIAL_SOCKET_DIR_MODE = "2703"
    }
}

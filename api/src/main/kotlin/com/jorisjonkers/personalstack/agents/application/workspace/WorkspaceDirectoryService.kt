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
}

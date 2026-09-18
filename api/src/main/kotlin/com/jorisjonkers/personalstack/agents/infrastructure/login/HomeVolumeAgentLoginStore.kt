package com.jorisjonkers.personalstack.agents.infrastructure.login

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentLoginStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads Agent Login presence straight off the `agent` user's home volume.
 *
 * The paths are the CLIs' own, not ours: Claude writes
 * `~/.claude/.credentials.json` and Codex writes `~/.codex/auth.json`, and
 * both rewrite them on every token refresh. Nothing in agents-api opens
 * them — presence is the whole answer, and reading the contents would put a
 * live OAuth token somewhere it does not need to be.
 *
 * The home directory is a parameter rather than `System.getenv("HOME")`:
 * the JVM runs as `api`, whose home is `/app`, while Agent Sessions run as
 * `agent` through `run-as-agent`, which sets `HOME=/home/agent`. Reading the
 * API's own `HOME` would answer about the wrong user's home every time.
 */
@Component
class HomeVolumeAgentLoginStore(
    private val agentHome: Path,
) : AgentLoginStore {
    // The Path constructor is what the tests use; Spring takes this one.
    @Autowired
    constructor(props: AgentRuntimeProperties) : this(Path.of(props.agentHome))

    override fun isPresent(kind: WorkspaceAgentKind): Boolean {
        val relative = LOGIN_FILES[kind] ?: return false
        val file = agentHome.resolve(relative)
        // An absent home volume is "not signed in yet", never a failure: a
        // developer laptop and the integration tests have no such mount
        // (fleet-infra#326). Size, not existence, because a truncated write
        // leaves an empty file that no CLI can authenticate with.
        return runCatching { Files.isRegularFile(file) && Files.size(file) > 0 }.getOrDefault(false)
    }

    override fun statuses(): List<AgentLoginStore.AgentLoginStatus> =
        LOGIN_FILES.keys.map { AgentLoginStore.AgentLoginStatus(kind = it, present = isPresent(it)) }

    private companion object {
        // Ordered: this is what agents-ui renders, and a stable order keeps
        // the list from reshuffling between polls.
        val LOGIN_FILES =
            linkedMapOf(
                WorkspaceAgentKind.CLAUDE to ".claude/.credentials.json",
                WorkspaceAgentKind.CODEX to ".codex/auth.json",
            )
    }
}

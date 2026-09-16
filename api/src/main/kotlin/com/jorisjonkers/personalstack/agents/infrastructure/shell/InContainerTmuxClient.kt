package com.jorisjonkers.personalstack.agents.infrastructure.shell

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.infrastructure.process.RunAsAgentCommandRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Wraps the tmux CLI for Shell Agent Sessions running inside this
 * container. Ported from agent-gateway's `TmuxClient`: every call still
 * shells out to the real `tmux` binary, now via run-as-agent so the
 * server — and everything it runs — is owned by `agent`, never `api`.
 *
 * One tmux server for the whole container (`-L socketName`), shared by
 * every Workspace's Shell Agent Session — a wedged server now stalls
 * every Workspace, not one (see [AgentRuntimeProperties.shellTmuxSocketName]).
 */
@Component
class InContainerTmuxClient(
    private val commands: RunAsAgentCommandRunner,
    private val props: AgentRuntimeProperties,
) {
    private val log = LoggerFactory.getLogger(InContainerTmuxClient::class.java)
    private val socketName get() = props.shellTmuxSocketName

    fun newSession(
        name: String,
        command: List<String>,
        cwd: String,
    ) {
        val argv =
            mutableListOf(
                "tmux",
                "-L",
                socketName,
                "new-session",
                "-d",
                "-s",
                name,
                "-x",
                "200",
                "-y",
                "50",
                "-c",
                cwd,
            ) + command
        commands.run(argv)
        // No client ever attaches to this session (output is tailed from the
        // pipe-pane log instead), so window-size=manual makes the browser's
        // resize frames the sole authority over pane geometry.
        commands.run(
            listOf("tmux", "-L", socketName, "set-option", "-t", name, "window-size", "manual"),
            checked = false,
        )
        commands.run(
            listOf("tmux", "-L", socketName, "set-option", "-g", "focus-events", "on"),
            checked = false,
        )
        log.info("tmux session {} created in {}", name, cwd)
    }

    fun killSession(name: String) {
        commands.run(listOf("tmux", "-L", socketName, "kill-session", "-t", name), checked = false)
    }

    fun sendKeys(
        session: String,
        text: String,
        enter: Boolean = true,
    ) {
        commands.run(listOf("tmux", "-L", socketName, "send-keys", "-t", "$session:0.0", "-l", text))
        if (enter) {
            commands.run(listOf("tmux", "-L", socketName, "send-keys", "-t", "$session:0.0", "Enter"))
        }
    }

    fun capture(
        session: String,
        historyLines: Int = 1_000,
    ): String =
        commands
            .run(
                listOf(
                    "tmux",
                    "-L",
                    socketName,
                    "capture-pane",
                    "-p",
                    "-S",
                    "-$historyLines",
                    "-t",
                    "$session:0.0",
                ),
            ).stdout

    /** Visible screen only, with ANSI escapes: the one-shot snapshot sent on attach. */
    fun captureWithEscapes(session: String): String =
        commands
            .run(listOf("tmux", "-L", socketName, "capture-pane", "-e", "-p", "-t", "$session:0.0"))
            .stdout

    fun resize(
        session: String,
        cols: Int,
        rows: Int,
    ) {
        commands.run(
            listOf(
                "tmux",
                "-L",
                socketName,
                "resize-window",
                "-t",
                "$session:0.0",
                "-x",
                cols.toString(),
                "-y",
                rows.toString(),
            ),
            checked = false,
        )
    }

    fun startPipeToFile(
        session: String,
        file: Path,
    ) {
        val target = file.toAbsolutePath().normalize()
        commands.run(
            listOf(
                "tmux",
                "-L",
                socketName,
                "pipe-pane",
                "-O",
                "-t",
                "$session:0.0",
                "cat >> ${shellQuote(target.toString())}",
            ),
        )
    }

    fun listSessions(): List<String> {
        val result =
            commands.run(
                listOf("tmux", "-L", socketName, "list-sessions", "-F", "#{session_name}"),
                checked = false,
            )
        if (result.exitCode != 0) return emptyList()
        return result.stdout.lines().filter { it.isNotBlank() }
    }

    fun sessionExists(name: String): Boolean = name in listSessions()

    internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

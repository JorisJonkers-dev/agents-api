package com.jorisjonkers.personalstack.agents.infrastructure.process

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import org.springframework.stereotype.Component
import java.io.File

/**
 * Prefixes every command with the `run-as-agent` helper (ADR 0003) so
 * tmux, mkdir and anything else this container shells out to on behalf
 * of an Agent Session always starts as `agent`, never as `api`. The
 * helper's path is configurable so a test can point it at a passthrough
 * script instead of the real setuid binary.
 */
@Component
class RunAsAgentCommandRunner(
    private val runner: ProcessRunner,
    private val props: AgentRuntimeProperties,
) {
    fun run(
        argv: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 30,
        checked: Boolean = true,
    ): ProcessRunner.Result = runner.run(listOf(props.runAsAgentPath) + argv, cwd, env, timeoutSeconds, checked)
}

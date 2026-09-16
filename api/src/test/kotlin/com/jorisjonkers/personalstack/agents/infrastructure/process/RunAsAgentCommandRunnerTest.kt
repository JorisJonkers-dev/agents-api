package com.jorisjonkers.personalstack.agents.infrastructure.process

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunAsAgentCommandRunnerTest {
    private val runner = mockk<ProcessRunner>(relaxed = true)

    @Test
    fun `prefixes every command with the configured run-as-agent path`() {
        val props =
            AgentRuntimeProperties(
                namespace = "agents-system",
                image = "ghcr.io/example/agent-runner:latest",
                serviceAccount = "agent-runner",
                claudeCredentialsPvc = "claude-credentials",
                codexCredentialsPvc = "codex-credentials",
                githubDeployKeySecret = "agents-github-deploy-key",
                runAsAgentPath = "/usr/local/lib/agents-api/run-as-agent",
            )
        val argv = slot<List<String>>()
        every { runner.run(capture(argv), any(), any(), any(), any()) } returns ProcessRunner.Result(0, "", "")

        RunAsAgentCommandRunner(runner, props).run(listOf("mkdir", "-p", "/workspaces/ws-1"))

        assertThat(argv.captured).containsExactly(
            "/usr/local/lib/agents-api/run-as-agent",
            "mkdir",
            "-p",
            "/workspaces/ws-1",
        )
    }

    @Test
    fun `a test passthrough path is a plain prefix like any other`() {
        val props =
            AgentRuntimeProperties(
                namespace = "agents-system",
                image = "ghcr.io/example/agent-runner:latest",
                serviceAccount = "agent-runner",
                claudeCredentialsPvc = "claude-credentials",
                codexCredentialsPvc = "codex-credentials",
                githubDeployKeySecret = "agents-github-deploy-key",
                runAsAgentPath = "/tmp/passthrough.sh",
            )
        val argv = slot<List<String>>()
        every { runner.run(capture(argv), any(), any(), any(), any()) } returns ProcessRunner.Result(0, "", "")

        RunAsAgentCommandRunner(runner, props).run(listOf("echo", "hi"))

        assertThat(argv.captured.first()).isEqualTo("/tmp/passthrough.sh")
        verify { runner.run(argv.captured, any(), any(), any(), any()) }
    }
}

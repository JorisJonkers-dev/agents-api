package com.jorisjonkers.personalstack.agents.infrastructure.shell

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.infrastructure.process.ProcessRunner
import com.jorisjonkers.personalstack.agents.infrastructure.process.RunAsAgentCommandRunner
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class InContainerTmuxClientTest {
    private val commands = mockk<RunAsAgentCommandRunner>(relaxed = true)
    private val props = runtimeProperties()
    private val client = InContainerTmuxClient(commands, props)

    @Test
    fun `newSession invokes tmux with the configured socket name and cwd`() {
        val calls = mutableListOf<List<String>>()
        every { commands.run(capture(calls), any(), any(), any(), any()) } returns ProcessRunner.Result(0, "", "")

        client.newSession("agent-a1b2c3d4-e5f6g7h8", listOf("/bin/bash", "-l"), "/workspaces/ws-1")

        val create = calls.single { it.contains("new-session") }
        assertThat(create).containsSubsequence("tmux", "-L", "agents-api")
        assertThat(create).contains("-s", "agent-a1b2c3d4-e5f6g7h8")
        assertThat(create).contains("-c", "/workspaces/ws-1")
        assertThat(create).endsWith("/bin/bash", "-l")

        val manualSize = calls.single { it.contains("window-size") }
        assertThat(
            manualSize,
        ).containsSubsequence("set-option", "-t", "agent-a1b2c3d4-e5f6g7h8", "window-size", "manual")

        val focusEvents = calls.single { it.contains("focus-events") }
        assertThat(focusEvents).containsSubsequence("set-option", "-g", "focus-events", "on")
    }

    @Test
    fun `sendKeys with enter sends the text then Enter as a separate invocation`() {
        every { commands.run(any(), any(), any(), any(), any()) } returns ProcessRunner.Result(0, "", "")

        client.sendKeys("agent-x", "hello world")

        verify {
            commands.run(
                match { it.containsAll(listOf("send-keys", "-t", "agent-x:0.0", "-l", "hello world")) },
                any(),
                any(),
                any(),
                any(),
            )
            commands.run(
                match { it.containsAll(listOf("send-keys", "-t", "agent-x:0.0", "Enter")) },
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `captureWithEscapes captures visible screen with ansi and no history flag`() {
        val argv = slot<List<String>>()
        every { commands.run(capture(argv), any(), any(), any(), any()) } returns
            ProcessRunner.Result(0, "screen with [31mansi[0m", "")

        val out = client.captureWithEscapes("agent-x")

        assertThat(out).contains("ansi")
        assertThat(argv.captured).containsSubsequence("tmux", "-L", "agents-api", "capture-pane", "-e", "-p")
        assertThat(argv.captured).contains("-t", "agent-x:0.0")
        assertThat(argv.captured).doesNotContain("-S")
    }

    @Test
    fun `resize invokes tmux resize-window with cols and rows`() {
        val argv = slot<List<String>>()
        every { commands.run(capture(argv), any(), any(), any(), any()) } returns ProcessRunner.Result(0, "", "")

        client.resize("agent-x", 120, 40)

        assertThat(argv.captured).containsSubsequence("resize-window", "-t", "agent-x:0.0")
        assertThat(argv.captured).containsSubsequence("-x", "120")
        assertThat(argv.captured).containsSubsequence("-y", "40")
    }

    @Test
    fun `startPipeToFile quotes the generated path for the tmux shell`() {
        val argv = slot<List<String>>()
        every { commands.run(capture(argv), any(), any(), any(), any()) } returns ProcessRunner.Result(0, "", "")

        client.startPipeToFile("agent-x", Path.of("/workspaces/ws-1/.agent-sessions/id's.log"))

        assertThat(argv.captured).containsSubsequence("pipe-pane", "-O", "-t", "agent-x:0.0")
        assertThat(argv.captured.last()).isEqualTo("cat >> '/workspaces/ws-1/.agent-sessions/id'\"'\"'s.log'")
    }

    @Test
    fun `listSessions returns empty list when tmux server is not running`() {
        every { commands.run(any(), any(), any(), any(), any()) } returns
            ProcessRunner.Result(1, "", "no server running")

        assertThat(client.listSessions()).isEmpty()
    }

    private fun runtimeProperties() =
        AgentRuntimeProperties(
            namespace = "agents-system",
            image = "ghcr.io/example/agent-runner:latest",
            serviceAccount = "agent-runner",
            claudeCredentialsPvc = "claude-credentials",
            codexCredentialsPvc = "codex-credentials",
            githubDeployKeySecret = "agents-github-deploy-key",
        )
}

package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.application.workspace.WorkspaceDirectoryService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.infrastructure.process.RunAsAgentCommandRunner
import com.jorisjonkers.personalstack.agents.infrastructure.shell.InContainerTmuxClient
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellAttachOperations
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellSessionRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.time.Instant

class InContainerAgentGatewayClientTest {
    private val tmux = mockk<InContainerTmuxClient>(relaxed = true)
    private val directories = mockk<WorkspaceDirectoryService>()
    private val registry = ShellSessionRegistry()
    private val commands = mockk<RunAsAgentCommandRunner>(relaxed = true)
    private val attach = ShellAttachOperations(tmux, registry)
    private val client = InContainerAgentGatewayClient(tmux, directories, registry, commands, attach)

    private val workspaceId = WorkspaceId.random()

    @Test
    fun `spawnAgent creates the directory-scoped tmux session and registers it`() {
        every { directories.ensureCreated(workspaceId) } returns Path.of("/workspaces/$workspaceId")

        val agent =
            client.spawnAgent(
                AgentGatewayClient.SpawnAgentRequest(workspace = workspace(), kind = WorkspaceAgentKind.SHELL),
            )

        assertThat(agent.kind).isEqualTo(WorkspaceAgentKind.SHELL)
        assertThat(agent.cwd).isEqualTo("/workspaces/$workspaceId")
        verify {
            tmux.newSession(
                match { it.startsWith("agent-${workspaceId.short()}-") },
                listOf("/bin/bash", "-l"),
                "/workspaces/$workspaceId",
            )
        }
    }

    @Test
    fun `spawnAgent rejects a non-Shell kind`() {
        assertThrows<IllegalArgumentException> {
            client.spawnAgent(
                AgentGatewayClient.SpawnAgentRequest(workspace = workspace(), kind = WorkspaceAgentKind.CODEX),
            )
        }
    }

    @Test
    fun `stopAgent kills the tmux session and drops it from the registry`() {
        every { directories.ensureCreated(workspaceId) } returns Path.of("/workspaces/$workspaceId")
        val agent =
            client.spawnAgent(
                AgentGatewayClient.SpawnAgentRequest(workspace = workspace(), kind = WorkspaceAgentKind.SHELL),
            )

        client.stopAgent(workspace(), agent.id)

        verify { tmux.killSession(match { it.startsWith("agent-${workspaceId.short()}-") }) }
        assertThrows<NoSuchElementException> { client.capture(workspace(), agent.id) }
    }

    @Test
    fun `operating on a different workspace's agent id is not-found, not a permission error`() {
        every { directories.ensureCreated(workspaceId) } returns Path.of("/workspaces/$workspaceId")
        val agent =
            client.spawnAgent(
                AgentGatewayClient.SpawnAgentRequest(workspace = workspace(), kind = WorkspaceAgentKind.SHELL),
            )
        val otherWorkspace = workspace(WorkspaceId.random())

        assertThrows<NoSuchElementException> { client.sendInput(otherWorkspace, agent.id, "ls", true) }
    }

    @Test
    fun `cleanupStableSession is a no-op, not an unsupported operation`() {
        client.cleanupStableSession(workspace(), AgentSessionId.random())
    }

    @Test
    fun `stageInput, clone, openPr and headless jobs are unsupported in this scope`() {
        assertThrows<UnsupportedOperationException> { client.stageInput(workspace(), "abc", "x", null) }
        assertThrows<UnsupportedOperationException> { client.clone(workspace(), "git@x") }
        assertThrows<UnsupportedOperationException> { client.openPr(workspace(), "/x", "t", "b") }
    }

    @Test
    fun `isReady is always true — no boot lease for an in-container session`() {
        assertThat(client.isReady(workspace())).isTrue()
    }

    private fun workspace(id: WorkspaceId = workspaceId) =
        Workspace(
            id = id,
            name = "playground",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.PENDING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            kind = WorkspaceKind.SCRATCH,
        )
}

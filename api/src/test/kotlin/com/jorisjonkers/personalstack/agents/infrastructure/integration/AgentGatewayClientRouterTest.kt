package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellSession
import com.jorisjonkers.personalstack.agents.infrastructure.shell.ShellSessionRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class AgentGatewayClientRouterTest {
    private val podGateway = mockk<HttpAgentGatewayClient>(relaxed = true)
    private val inContainerGateway = mockk<InContainerAgentGatewayClient>(relaxed = true)
    private val registry = ShellSessionRegistry()
    private val router = AgentGatewayClientRouter(podGateway, inContainerGateway, registry)

    @Test
    fun `spawnAgent for a Shell kind in a Scratch workspace goes in-container`() {
        val workspace = workspace(WorkspaceKind.SCRATCH)
        val request = AgentGatewayClient.SpawnAgentRequest(workspace = workspace, kind = WorkspaceAgentKind.SHELL)
        every { inContainerGateway.spawnAgent(request) } returns
            AgentGatewayClient.GatewayAgent(id = "abc", kind = WorkspaceAgentKind.SHELL, cwd = "/workspaces/x")

        router.spawnAgent(request)

        verify { inContainerGateway.spawnAgent(request) }
        verify(exactly = 0) { podGateway.spawnAgent(any()) }
    }

    @Test
    fun `spawnAgent for a repo-backed workspace goes to the pod gateway`() {
        val workspace = workspace(WorkspaceKind.REPO_BACKED)
        val request = AgentGatewayClient.SpawnAgentRequest(workspace = workspace, kind = WorkspaceAgentKind.CLAUDE)
        every { podGateway.spawnAgent(request) } returns
            AgentGatewayClient.GatewayAgent(id = "abc", kind = WorkspaceAgentKind.CLAUDE, cwd = "/workspace")

        router.spawnAgent(request)

        verify { podGateway.spawnAgent(request) }
        verify(exactly = 0) { inContainerGateway.spawnAgent(any()) }
    }

    @Test
    fun `sendInput for a Shell Agent Session this container spawned goes in-container`() {
        val workspace = workspace(WorkspaceKind.SCRATCH)
        registry.put(shellSession(workspace, "abc"))

        router.sendInput(workspace, "abc", "ls", true)

        verify { inContainerGateway.sendInput(workspace, "abc", "ls", true) }
        verify(exactly = 0) { podGateway.sendInput(any(), any(), any(), any()) }
    }

    @Test
    fun `stopAgent for a Pod-bound session in a Scratch workspace still goes to the pod gateway`() {
        // A Claude Agent Session started in a Scratch Workspace before #62 is bound to a
        // Pod. Routing it in-container would find no such session, drop the stop and leave
        // the process running.
        val workspace = workspace(WorkspaceKind.SCRATCH)

        router.stopAgent(workspace, "pod-owned")

        verify { podGateway.stopAgent(workspace, "pod-owned") }
        verify(exactly = 0) { inContainerGateway.stopAgent(any(), any()) }
    }

    @Test
    fun `a Shell Agent Session id from another Workspace does not route in-container`() {
        val mine = workspace(WorkspaceKind.SCRATCH)
        val other = workspace(WorkspaceKind.SCRATCH)
        registry.put(shellSession(other, "abc"))

        router.stopAgent(mine, "abc")

        verify { podGateway.stopAgent(mine, "abc") }
        verify(exactly = 0) { inContainerGateway.stopAgent(any(), any()) }
    }

    @Test
    fun `stopAgent for a repo-backed workspace goes to the pod gateway`() {
        val workspace = workspace(WorkspaceKind.REPO_BACKED)

        router.stopAgent(workspace, "abc")

        verify { podGateway.stopAgent(workspace, "abc") }
        verify(exactly = 0) { inContainerGateway.stopAgent(any(), any()) }
    }

    private fun shellSession(
        workspace: Workspace,
        id: String,
    ) = ShellSession(
        gatewayAgentId = id,
        workspaceId = workspace.id,
        tmuxSessionName = "agent-x-$id",
        cwd = "/workspaces/x",
        logFile = Path.of("/workspaces/x/.agent-sessions/$id.log"),
        createdAt = Instant.now(),
    )

    private fun workspace(kind: WorkspaceKind) =
        Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = if (kind == WorkspaceKind.SCRATCH) null else "http://gw:8090",
            status = WorkspaceStatus.PREPARING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            kind = kind,
        )
}

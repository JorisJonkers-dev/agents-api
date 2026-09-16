package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant

class AgentGatewayClientRouterTest {
    private val podGateway = mockk<HttpAgentGatewayClient>(relaxed = true)
    private val inContainerGateway = mockk<InContainerAgentGatewayClient>(relaxed = true)
    private val router = AgentGatewayClientRouter(podGateway, inContainerGateway)

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
    fun `sendInput for a Scratch workspace goes in-container without needing the kind`() {
        val workspace = workspace(WorkspaceKind.SCRATCH)

        router.sendInput(workspace, "abc", "ls", true)

        verify { inContainerGateway.sendInput(workspace, "abc", "ls", true) }
        verify(exactly = 0) { podGateway.sendInput(any(), any(), any(), any()) }
    }

    @Test
    fun `stopAgent for a repo-backed workspace goes to the pod gateway`() {
        val workspace = workspace(WorkspaceKind.REPO_BACKED)

        router.stopAgent(workspace, "abc")

        verify { podGateway.stopAgent(workspace, "abc") }
        verify(exactly = 0) { inContainerGateway.stopAgent(any(), any()) }
    }

    private fun workspace(kind: WorkspaceKind) =
        Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = if (kind == WorkspaceKind.SCRATCH) null else "http://gw:8090",
            status = WorkspaceStatus.PENDING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            kind = kind,
        )
}

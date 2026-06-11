package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class OpenPullRequestCommandHandlerTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val gateway = mockk<AgentGatewayClient>(relaxed = true)
    private val handler = OpenPullRequestCommandHandler(workspaces, gateway)

    @Test
    fun `handle delegates to the gateway with the resolved workspace`() {
        val id = WorkspaceId.random()
        val ws = workspace(id)
        every { workspaces.findById(id) } returns ws

        handler.handle(
            OpenPullRequestCommand(
                workspaceId = id,
                repoDir = "/workspace/repo",
                title = "Fix typo",
                body = "Body",
            ),
        )

        verify { gateway.openPr(ws, "/workspace/repo", "Fix typo", "Body", "main") }
    }

    @Test
    fun `handle errors for unknown workspaces`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns null
        assertThrows<IllegalStateException> {
            handler.handle(OpenPullRequestCommand(id, "/x", "t", "b"))
        }
    }

    private fun workspace(id: WorkspaceId) =
        Workspace(
            id = id,
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = "http://x:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

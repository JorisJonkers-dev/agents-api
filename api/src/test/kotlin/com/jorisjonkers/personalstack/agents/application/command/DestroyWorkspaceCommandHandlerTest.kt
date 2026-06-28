package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class DestroyWorkspaceCommandHandlerTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val orchestrator = mockk<AgentRunnerOrchestrator>(relaxed = true)
    private val handler = DestroyWorkspaceCommandHandler(workspaces, orchestrator)

    @Test
    fun `handle destroys the pod and marks workspace destroyed`() {
        val id = WorkspaceId.random()
        val ws = workspace(id)
        every { workspaces.findById(id) } returns ws
        val saved = slot<Workspace>()
        every { workspaces.save(capture(saved)) } answers { saved.captured }

        handler.handle(DestroyWorkspaceCommand(id))

        verify { orchestrator.destroy(ws) }
        assertThat(saved.captured.status).isEqualTo(WorkspaceStatus.DESTROYED)
    }

    @Test
    fun `handle is a no-op for unknown workspaces`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns null
        handler.handle(DestroyWorkspaceCommand(id))
        verify(exactly = 0) { orchestrator.destroy(any()) }
    }

    @Test
    fun `handle rejects workspace with pending runner setup`() {
        val id = WorkspaceId.random()
        val ws =
            workspace(id)
                .copy(
                    pendingRunnerSetupId = AgentSetupId("gpu"),
                    pendingRunnerSetupVersion = AgentSetupVersion(2),
                )
        every { workspaces.findById(id) } returns ws

        assertThrows<IllegalArgumentException> {
            handler.handle(DestroyWorkspaceCommand(id))
        }
        verify(exactly = 0) { orchestrator.destroy(any()) }
    }

    private fun workspace(id: WorkspaceId) =
        Workspace(
            id = id,
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = "agent-runner-abcdef01",
            pvcName = "workspace-abcdef01",
            gatewayEndpoint = "http://x:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

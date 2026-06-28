package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class DetachWorkspaceRepositoryCommandHandlerTest {
    private val links = mockk<WorkspaceRepositoryRepository>(relaxed = true)
    private val handler = DetachWorkspaceRepositoryCommandHandler(links)

    private val workspaceId = WorkspaceId.random()
    private val repositoryId = RepositoryId.random()
    private val command = DetachWorkspaceRepositoryCommand(workspaceId, repositoryId)

    private fun link(isPrimary: Boolean) =
        WorkspaceRepositoryRepository.Link(workspaceId, repositoryId, isPrimary, Instant.now())

    @Test
    fun `detaches a non-primary repository`() {
        every { links.findAllByWorkspaceId(workspaceId) } returns listOf(link(isPrimary = false))

        handler.handle(command)

        verify { links.detach(workspaceId, repositoryId) }
    }

    @Test
    fun `refuses to detach the primary repository`() {
        every { links.findAllByWorkspaceId(workspaceId) } returns listOf(link(isPrimary = true))

        assertThrows<IllegalArgumentException> { handler.handle(command) }
        verify(exactly = 0) { links.detach(any(), any()) }
    }

    @Test
    fun `is a no-op-safe detach when no link exists`() {
        every { links.findAllByWorkspaceId(workspaceId) } returns emptyList()

        handler.handle(command)

        verify { links.detach(workspaceId, repositoryId) }
    }
}

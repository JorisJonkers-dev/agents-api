package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class UnlinkRepositoryFromProjectCommandHandlerTest {
    private val junction = mockk<ProjectRepositoryRepository>()
    private val handler = UnlinkRepositoryFromProjectCommandHandler(junction)

    @Test
    fun `handle delegates to junction unlink`() {
        val pid = ProjectId.random()
        val rid = RepositoryId.random()
        every { junction.unlink(pid, rid) } returns Unit
        handler.handle(UnlinkRepositoryFromProjectCommand(pid, rid))
        verify { junction.unlink(pid, rid) }
    }

    @Test
    fun `handle is idempotent on a missing junction row`() {
        val pid = ProjectId.random()
        val rid = RepositoryId.random()
        every { junction.unlink(pid, rid) } returns Unit
        assertDoesNotThrow {
            handler.handle(UnlinkRepositoryFromProjectCommand(pid, rid))
        }
    }
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

class DeleteRepositoryCommandHandlerTest {
    private val repositories = mockk<RepositoryRepository>()
    private val deployKeys = mockk<DeployKeyStore>()
    private val provider =
        mockk<ObjectProvider<DeployKeyStore>> {
            every { ifAvailable } returns deployKeys
        }
    private val handler = DeleteRepositoryCommandHandler(repositories, provider)

    private fun repository() =
        Repository(
            id = RepositoryId.random(),
            name = "n",
            repoUrl = "u",
            defaultBranch = "main",
            vaultKeyPath = "x",
            deployKeyFingerprint = null,
            deployKeyAddedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `handle deletes the repository and best-effort removes the vault key`() {
        val r = repository()
        every { repositories.findById(r.id) } returns r
        every { deployKeys.remove(r.id) } returns Unit
        every { repositories.delete(r.id) } returns Unit

        handler.handle(DeleteRepositoryCommand(r.id))

        verify { deployKeys.remove(r.id) }
        verify { repositories.delete(r.id) }
    }

    @Test
    fun `handle proceeds when vault is offline`() {
        val r = repository()
        every { repositories.findById(r.id) } returns r
        every { deployKeys.remove(r.id) } throws RuntimeException("vault down")
        every { repositories.delete(r.id) } returns Unit

        handler.handle(DeleteRepositoryCommand(r.id))

        verify { repositories.delete(r.id) }
    }

    @Test
    fun `handle is idempotent on a missing repository`() {
        every { repositories.findById(any()) } returns null
        handler.handle(DeleteRepositoryCommand(RepositoryId.random()))
        verify(exactly = 0) { repositories.delete(any()) }
    }
}

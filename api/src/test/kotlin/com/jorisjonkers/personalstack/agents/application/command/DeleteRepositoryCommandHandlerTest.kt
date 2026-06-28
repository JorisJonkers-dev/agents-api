package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.common.vault.VaultKeyValueWriter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

class DeleteRepositoryCommandHandlerTest {
    private val repositories = mockk<RepositoryRepository>()
    private val vaultWriter = mockk<VaultKeyValueWriter>()
    private val provider =
        mockk<ObjectProvider<VaultKeyValueWriter>> {
            every { ifAvailable } returns vaultWriter
        }
    private val handler = DeleteRepositoryCommandHandler(repositories, provider)

    private fun repository() =
        Repository(
            id = RepositoryId.random(),
            name = "n",
            repoUrl = "u",
            defaultBranch = "main",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `handle deletes the repository and best-effort removes any leftover vault secret`() {
        val r = repository()
        every { repositories.findById(r.id) } returns r
        every { vaultWriter.deleteSecret(any()) } returns Unit
        every { repositories.delete(r.id) } returns Unit

        handler.handle(DeleteRepositoryCommand(r.id))

        verify { vaultWriter.deleteSecret("secret/data/agents/repositories/${r.id}") }
        verify { repositories.delete(r.id) }
    }

    @Test
    fun `handle proceeds when vault is offline`() {
        val r = repository()
        every { repositories.findById(r.id) } returns r
        every { vaultWriter.deleteSecret(any()) } throws RuntimeException("vault down")
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

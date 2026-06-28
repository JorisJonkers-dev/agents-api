package com.jorisjonkers.personalstack.agents.application

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RepositoryVerificationServiceTest {
    private val repositories = mockk<RepositoryRepository>()
    private val verifyAccess = mockk<VerifyRepositoryAccess>()
    private val service = RepositoryVerificationService(repositories, verifyAccess)

    private val sample =
        Repository(
            id = RepositoryId.random(),
            name = "agents",
            repoUrl = "git@github.com:JorisJonkers-dev/agents.git",
            defaultBranch = "main",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `reverify persists the fresh outcome and returns the updated row`() {
        every { repositories.findById(sample.id) } returns sample
        every { verifyAccess.verify(sample.repoUrl, sample.defaultBranch) } returns
            VerifyRepositoryAccess.Result(
                defaultBranchProtected = false,
                checkedAt = Instant.now(),
                messages = listOf("default branch 'main' is NOT protected on GitHub"),
            )
        val saved = slot<Repository>()
        every { repositories.save(capture(saved)) } answers { saved.captured }

        val result = service.reverify(sample.id)

        assertThat(result).isNotNull
        assertThat(saved.captured.verification?.defaultBranchProtected).isFalse
        assertThat(saved.captured.verification?.messages).anyMatch { it.contains("NOT protected") }
    }

    @Test
    fun `reverify returns null for an unknown repository`() {
        every { repositories.findById(any()) } returns null
        assertThat(service.reverify(RepositoryId.random())).isNull()
    }
}

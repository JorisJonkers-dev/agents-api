package com.jorisjonkers.personalstack.agents.application

import com.jorisjonkers.personalstack.agents.domain.port.BranchProtectionClient
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VerifyRepositoryAccessTest {
    private val branchProtection = mockk<BranchProtectionClient>()
    private val clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
    private val useCase = VerifyRepositoryAccess(branchProtection, clock)

    private val repoUrl = "git@github.com:ExtraToast/agents.git"

    @Test
    fun `protected default branch produces a clean result with no warnings`() {
        every { branchProtection.isBranchProtected(repoUrl, "main") } returns true

        val result = useCase.verify(repoUrl, "main")

        assertThat(result.defaultBranchProtected).isTrue
        assertThat(result.checkedAt).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
        assertThat(result.messages).isEmpty()
    }

    @Test
    fun `unprotected default branch is a loud warning`() {
        every { branchProtection.isBranchProtected(repoUrl, "main") } returns false

        val result = useCase.verify(repoUrl, "main")

        assertThat(result.defaultBranchProtected).isFalse
        assertThat(result.messages).anyMatch { it.contains("NOT protected") }
    }

    @Test
    fun `null branch protection (no token) degrades to null with a message`() {
        every { branchProtection.isBranchProtected(repoUrl, "main") } returns null

        val result = useCase.verify(repoUrl, "main")

        assertThat(result.defaultBranchProtected).isNull()
        assertThat(result.messages).anyMatch { it.contains("could not be determined") }
    }
}

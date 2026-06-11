package com.jorisjonkers.personalstack.agents.application

import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.BranchProtectionClient
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VerifyRepositoryAccessTest {
    private val gateway = mockk<AgentGatewayClient>()
    private val branchProtection = mockk<BranchProtectionClient>()
    private val clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
    private val useCase = VerifyRepositoryAccess(gateway, branchProtection, clock)

    private val repoUrl = "git@github.com:ExtraToast/agents.git"

    @Test
    fun `flattens read-write + protected into a clean result with no warnings`() {
        every { gateway.verifyAccess(repoUrl, "main") } returns
            AgentGatewayClient.AccessVerification(read = true, write = true, detail = "ok")
        every { branchProtection.isBranchProtected(repoUrl, "main") } returns true

        val result = useCase.verify(repoUrl, "main")

        assertThat(result.read).isTrue
        assertThat(result.write).isTrue
        assertThat(result.defaultBranchProtected).isTrue
        assertThat(result.checkedAt).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
        assertThat(result.messages).isEmpty()
    }

    @Test
    fun `read false produces a read message`() {
        every { gateway.verifyAccess(repoUrl, "main") } returns
            AgentGatewayClient.AccessVerification(read = false, write = false, detail = "Permission denied")
        every { branchProtection.isBranchProtected(repoUrl, "main") } returns true

        val result = useCase.verify(repoUrl, "main")

        assertThat(result.read).isFalse
        assertThat(result.messages).anyMatch { it.contains("cannot read") }
    }

    @Test
    fun `write false produces a read-only warning but keeps read true`() {
        every { gateway.verifyAccess(repoUrl, "main") } returns
            AgentGatewayClient.AccessVerification(read = true, write = false, detail = "denied")
        every { branchProtection.isBranchProtected(repoUrl, "main") } returns true

        val result = useCase.verify(repoUrl, "main")

        assertThat(result.read).isTrue
        assertThat(result.write).isFalse
        assertThat(result.messages).anyMatch { it.contains("read-only") }
    }

    @Test
    fun `null gateway result degrades to null read-write with a warning`() {
        every { gateway.verifyAccess(repoUrl, "main") } returns null
        every { branchProtection.isBranchProtected(repoUrl, "main") } returns true

        val result = useCase.verify(repoUrl, "main")

        assertThat(result.read).isNull()
        assertThat(result.write).isNull()
        assertThat(result.messages).anyMatch { it.contains("could not be verified") }
    }

    @Test
    fun `unprotected default branch is a loud warning`() {
        every { gateway.verifyAccess(repoUrl, "main") } returns
            AgentGatewayClient.AccessVerification(read = true, write = true, detail = "ok")
        every { branchProtection.isBranchProtected(repoUrl, "main") } returns false

        val result = useCase.verify(repoUrl, "main")

        assertThat(result.defaultBranchProtected).isFalse
        assertThat(result.messages).anyMatch { it.contains("NOT protected") }
    }

    @Test
    fun `null branch protection (no token) degrades to null with a message`() {
        every { gateway.verifyAccess(repoUrl, "main") } returns
            AgentGatewayClient.AccessVerification(read = true, write = true, detail = "ok")
        every { branchProtection.isBranchProtected(repoUrl, "main") } returns null

        val result = useCase.verify(repoUrl, "main")

        assertThat(result.defaultBranchProtected).isNull()
        assertThat(result.messages).anyMatch { it.contains("could not be determined") }
    }
}

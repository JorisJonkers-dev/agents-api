package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.infrastructure.integration.GitHubAppInstallationTokenClient
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant

class InternalGitHubTokenControllerTest {
    private val tokens = mockk<GitHubAppInstallationTokenClient>()
    private val controller = InternalGitHubTokenController(tokens)

    private val request =
        InternalGitHubTokenController.InstallationTokenRequest(repoUrl = "git@github.com:ExtraToast/agents.git")

    @Test
    fun `503 when minting is disabled`() {
        every { tokens.enabled } returns false
        val resp = controller.installationToken(request)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `400 when repoUrl is missing or blank`() {
        every { tokens.enabled } returns true
        val nullRepo = InternalGitHubTokenController.InstallationTokenRequest(null)
        val blankRepo = InternalGitHubTokenController.InstallationTokenRequest("  ")
        assertThat(controller.installationToken(nullRepo).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(controller.installationToken(blankRepo).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `502 when the App is configured but GitHub minting fails`() {
        every { tokens.enabled } returns true
        every { tokens.mint(any()) } returns null
        val resp = controller.installationToken(request)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
    }

    @Test
    fun `200 with the token and expiry on success`() {
        val expiry = Instant.parse("2026-06-02T15:00:00Z")
        every { tokens.enabled } returns true
        every { tokens.mint(any()) } returns GitHubAppInstallationTokenClient.InstallationToken("ghs_abc", expiry)
        val resp = controller.installationToken(request)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body?.token).isEqualTo("ghs_abc")
        assertThat(resp.body?.expiresAt).isEqualTo(expiry)
    }
}

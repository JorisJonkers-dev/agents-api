package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.infrastructure.integration.GitHubAppInstallationTokenClient
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * In-cluster only: the runner's `gh` wrapper calls this to obtain a
 * fresh, single-repo GitHub App installation token for runner GitHub
 * writes (`git push`, `gh pr create`, PR comments, Actions re-runs).
 * Reached over svc.cluster.local, so it bypasses the
 * edge forward-auth that gates the public API; the
 * InternalBearerAuthFilter (see SecurityConfig) gates it with a shared
 * bearer instead, and is fail-closed when the bearer is unset.
 *
 * 503 when minting is disabled (no App configured); 502 when the App
 * is configured but GitHub minting failed (not installed on the owner,
 * transport error, …) so the caller can fall back to read-only `gh`.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/internal/github")
class InternalGitHubTokenController(
    private val tokens: GitHubAppInstallationTokenClient,
) {
    data class InstallationTokenRequest(
        val repoUrl: String? = null,
    )

    data class InstallationTokenResponse(
        val token: String,
        val expiresAt: Instant,
    )

    @PostMapping("/installation-token")
    fun installationToken(
        @RequestBody request: InstallationTokenRequest,
    ): ResponseEntity<InstallationTokenResponse> {
        if (!tokens.enabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }
        val repoUrl = request.repoUrl?.takeIf { it.isNotBlank() } ?: return ResponseEntity.badRequest().build()
        return tokens
            .mint(repoUrl)
            ?.let { ResponseEntity.ok(InstallationTokenResponse(it.token, it.expiresAt)) }
            ?: ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
    }
}

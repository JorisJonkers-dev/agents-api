package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.port.BranchProtectionClient
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * GitHub REST adapter for `GET /repos/{owner}/{repo}/branches/{branch}/protection`.
 * Uses a read-only token from [AgentRuntimeProperties.githubApiToken];
 * an empty token, an unparseable repo URL, a 404, or any transport
 * failure all yield `null` so branch protection never blocks a flow.
 */
@Component
class GitHubBranchProtectionClient(
    private val restClient: RestClient,
    private val props: AgentRuntimeProperties,
) : BranchProtectionClient {
    private val log = LoggerFactory.getLogger(GitHubBranchProtectionClient::class.java)

    override fun isBranchProtected(
        repoUrl: String,
        branch: String,
    ): Boolean? {
        val token = props.githubApiToken.trim()
        if (token.isEmpty()) {
            log.info("branch-protection check skipped — GITHUB_API_TOKEN not configured")
            return null
        }
        val slug = parseOwnerRepo(repoUrl)
        if (slug == null) {
            log.warn("branch-protection check skipped — could not parse owner/repo from {}", repoUrl)
            return null
        }
        val base = props.githubApiBaseUrl.trim().trimEnd('/')
        return runCatching {
            var protected = false
            restClient
                .get()
                .uri("$base/repos/${slug.owner}/${slug.repo}/branches/$branch/protection")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                // 404 = no protection or token can't see it; 401/403 =
                // inconclusive. Map all client errors to "not true"
                // without throwing so the runCatching below only trips
                // on transport faults.
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError) { _, _ -> }
                .toBodilessEntity()
                .also { resp -> protected = resp.statusCode.is2xxSuccessful }
            protected
        }.onFailure {
            log.warn("branch-protection check for {} failed: {}", repoUrl, it.message)
        }.getOrNull()
    }

    data class OwnerRepo(
        val owner: String,
        val repo: String,
    )

    companion object {
        // git@github.com:Owner/Repo(.git) | ssh://git@github.com/Owner/Repo(.git)
        // https://github.com/Owner/Repo(.git) | http://...
        private val SCP_LIKE = Regex("""^[^@]+@[^:]+:(?<owner>[^/]+)/(?<repo>[^/]+?)(?:\.git)?/?$""")
        private val URL_LIKE = Regex("""^[a-zA-Z]+://(?:[^@/]+@)?[^/]+/(?<owner>[^/]+)/(?<repo>[^/]+?)(?:\.git)?/?$""")

        fun parseOwnerRepo(repoUrl: String): OwnerRepo? {
            val trimmed = repoUrl.trim()
            val match = SCP_LIKE.matchEntire(trimmed) ?: URL_LIKE.matchEntire(trimmed) ?: return null
            val owner = match.groups["owner"]?.value?.takeIf { it.isNotBlank() }
            val repo = match.groups["repo"]?.value?.takeIf { it.isNotBlank() }
            return if (owner != null && repo != null) OwnerRepo(owner, repo) else null
        }
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.credential

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.GitHubBranchProtectionClient

/**
 * Decides whether a requested repo URL is one of a Workspace's own
 * Repositories — the gate that keeps one Workspace from minting a token
 * for a repo it was never given (#63). Comparison is by parsed
 * owner/repo slug, not raw string equality, so `git@github.com:...` and
 * `https://github.com/....git` forms of the same repo match.
 */
object GitCredentialRepoScope {
    sealed interface Decision {
        data class Allowed(
            val requested: Repository,
            val workspaceRepos: List<Repository>,
        ) : Decision

        data class Denied(
            val reason: String,
        ) : Decision
    }

    fun resolve(
        requestedRepoUrl: String,
        workspaceRepos: List<Repository>,
    ): Decision {
        val requestedSlug =
            GitHubBranchProtectionClient.parseOwnerRepo(requestedRepoUrl)
                ?: return Decision.Denied("unparseable repository URL")
        val match =
            workspaceRepos.firstOrNull { GitHubBranchProtectionClient.parseOwnerRepo(it.repoUrl) == requestedSlug }
                ?: return Decision.Denied("repository is not attached to this workspace")
        return Decision.Allowed(match, workspaceRepos)
    }
}

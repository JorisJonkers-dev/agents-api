package com.jorisjonkers.personalstack.agents.application.credential

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import com.jorisjonkers.personalstack.agents.infrastructure.credential.GitCredentialRepoScope
import com.jorisjonkers.personalstack.agents.infrastructure.integration.GitHubAppInstallationTokenClient
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * The application-side of the git-credential unix socket (#63): given a
 * Workspace (identified by which socket the caller connected to, never
 * by anything the caller sends) and the repo it wants to push/pull,
 * mints a token scoped to that Workspace's own Repositories — never any
 * repo the App merely happens to be installed on.
 */
@Service
class WorkspaceGitCredentialService(
    private val workspaces: WorkspaceRepository,
    private val workspaceRepositories: WorkspaceRepositoryRepository,
    private val repositories: RepositoryRepository,
    private val tokens: GitHubAppInstallationTokenClient,
) {
    sealed interface Result {
        data class Success(
            val token: String,
            val expiresAt: Instant,
        ) : Result

        data class Denied(
            val reason: String,
        ) : Result
    }

    fun mintFor(
        workspaceId: WorkspaceId,
        requestedRepoUrl: String,
    ): Result {
        val workspace = workspaces.findById(workspaceId) ?: return Result.Denied("unknown workspace")
        if (!tokens.enabled) return Result.Denied("github app token minting is disabled")
        val workspaceRepos = resolveRepositories(workspace)
        return when (val decision = GitCredentialRepoScope.resolve(requestedRepoUrl, workspaceRepos)) {
            is GitCredentialRepoScope.Decision.Denied -> Result.Denied(decision.reason)
            is GitCredentialRepoScope.Decision.Allowed -> mint(decision)
        }
    }

    private fun mint(allowed: GitCredentialRepoScope.Decision.Allowed): Result {
        val siblingUrls = allowed.workspaceRepos.map { it.repoUrl }.toSet() - allowed.requested.repoUrl
        val minted = tokens.mint(allowed.requested.repoUrl, siblingUrls) ?: return Result.Denied("token mint failed")
        return Result.Success(minted.token, minted.expiresAt)
    }

    private fun resolveRepositories(workspace: Workspace): List<Repository> {
        val ids = workspaceRepositories.findAllByWorkspaceId(workspace.id).map { it.repositoryId }.toMutableSet()
        workspace.repositoryId?.let { ids += it }
        return ids.mapNotNull { repositories.findById(it) }
    }
}

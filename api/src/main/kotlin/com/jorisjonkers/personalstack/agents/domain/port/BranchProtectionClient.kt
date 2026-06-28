package com.jorisjonkers.personalstack.agents.domain.port

/**
 * Driven port for the GitHub branch-protection lookup. Implementations
 * resolve owner/repo from a deploy-key repo URL (ssh or https) and ask
 * the GitHub REST API whether the default branch is protected.
 *
 * The result is intentionally three-valued: a missing token, a 404
 * (no protection / no permission), or any transport error all collapse
 * to `null` — the caller treats that as "inconclusive", never a hard
 * failure.
 */
interface BranchProtectionClient {
    /**
     * @return `true` when the branch has protection configured,
     *   `false` when GitHub reports it explicitly unprotected, and
     *   `null` when the answer is inconclusive (no token, parse
     *   failure, 404, network/auth error).
     */
    fun isBranchProtected(
        repoUrl: String,
        branch: String,
    ): Boolean?
}

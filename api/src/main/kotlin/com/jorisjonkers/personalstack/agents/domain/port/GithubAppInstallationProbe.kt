package com.jorisjonkers.personalstack.agents.domain.port

/**
 * Driven port for a live, read-only check of whether the platform's
 * GitHub App is installed for a repository and can access it. The probe
 * never mints or returns an access token — it only reports state.
 */
interface GithubAppInstallationProbe {
    /**
     * @return [GithubAppInstallationState.INSTALLED] when an installation
     *   that can access the repo exists, [GithubAppInstallationState.NOT_INSTALLED]
     *   when GitHub reports no such installation (owner not installed or
     *   the repo is excluded), and [GithubAppInstallationState.UNKNOWN]
     *   when the answer is inconclusive (App not configured, URL not a
     *   GitHub repo, auth/transport error, rate limiting).
     */
    fun probe(repoUrl: String): GithubAppInstallationState
}

enum class GithubAppInstallationState {
    INSTALLED,
    NOT_INSTALLED,
    UNKNOWN,
}

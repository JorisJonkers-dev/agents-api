package com.jorisjonkers.personalstack.agents.application

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.GithubAppInstallationProbe
import com.jorisjonkers.personalstack.agents.domain.port.GithubAppInstallationState
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Live, on-demand GitHub App installation status for a repository,
 * exposed via GET /repositories/{id}/installation-status. The same GET
 * backs both the initial screen load and the manual "re-check" action —
 * it queries GitHub fresh every call and stores nothing. The probe never
 * mints a token; UNKNOWN is a legitimate, explicit outcome (App not
 * configured, URL not a GitHub repo, rate limiting, transport error).
 */
@Service
class RepositoryInstallationStatusService(
    private val repositories: RepositoryRepository,
    private val probe: GithubAppInstallationProbe,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Status(
        val state: GithubAppInstallationState,
        val checkedAt: Instant,
        val detail: String?,
    )

    /** @return the live status, or null when the repository does not exist. */
    fun status(id: RepositoryId): Status? {
        val repository = repositories.findById(id) ?: return null
        val state = probe.probe(repository.repoUrl)
        val detail =
            if (state == GithubAppInstallationState.UNKNOWN) {
                "Could not determine whether the GitHub App can access this repository."
            } else {
                null
            }
        return Status(state = state, checkedAt = Instant.now(clock), detail = detail)
    }
}

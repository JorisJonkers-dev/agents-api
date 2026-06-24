package com.jorisjonkers.personalstack.agents.application

import com.jorisjonkers.personalstack.agents.domain.model.AccessVerification
import com.jorisjonkers.personalstack.agents.domain.port.BranchProtectionClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Branch-protection check for a repository's default branch, flattened
 * into the result shape the UI renders. GitHub App access readiness is
 * reported separately by the live install-status probe; this service
 * only answers "is the default branch protected?". The check degrades
 * gracefully — a missing token or transport error leaves protection
 * null (inconclusive) rather than throwing.
 */
@Service
class VerifyRepositoryAccess(
    private val branchProtection: BranchProtectionClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Result(
        val defaultBranchProtected: Boolean?,
        val checkedAt: Instant,
        val messages: List<String>,
    ) {
        fun toAccessVerification() =
            AccessVerification(
                defaultBranchProtected = defaultBranchProtected,
                checkedAt = checkedAt,
                messages = messages,
            )
    }

    fun verify(
        repoUrl: String,
        defaultBranch: String,
    ): Result {
        val messages = mutableListOf<String>()

        val protected = branchProtection.isBranchProtected(repoUrl, defaultBranch)
        when (protected) {
            null -> messages += "branch protection on '$defaultBranch' could not be determined"
            false -> messages += "default branch '$defaultBranch' is NOT protected on GitHub"
            true -> Unit
        }

        return Result(
            defaultBranchProtected = protected,
            checkedAt = Instant.now(clock),
            messages = messages,
        )
    }
}

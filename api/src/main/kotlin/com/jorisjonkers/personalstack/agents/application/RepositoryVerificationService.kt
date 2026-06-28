package com.jorisjonkers.personalstack.agents.application

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * On-demand re-verification of a repository's deploy-key access,
 * exposed via POST /repositories/{id}/verify. Loads the row, runs the
 * combined gateway + branch-protection probe, persists the outcome,
 * and returns the refreshed repository so the controller can render
 * the new status without a second round-trip.
 */
@Service
class RepositoryVerificationService(
    private val repositories: RepositoryRepository,
    private val verifyAccess: VerifyRepositoryAccess,
) {
    @Transactional
    fun reverify(id: RepositoryId): Repository? {
        val repository = repositories.findById(id) ?: return null
        val result = verifyAccess.verify(repository.repoUrl, repository.defaultBranch)
        return repositories.save(
            repository.copy(
                updatedAt = Instant.now(),
                verification = result.toAccessVerification(),
            ),
        )
    }
}

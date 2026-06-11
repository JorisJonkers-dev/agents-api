package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant

/**
 * A GitHub repository plus its deploy key. Repositories live
 * independently of any Project — many Projects can link the same
 * repository through the project_repositories junction. The deploy
 * key for the repo lives at `vaultKeyPath`; the fingerprint is
 * mirrored into the row so the UI can render it without a Vault
 * round-trip.
 *
 * `deployKeyFingerprint` is nullable so a row can sit briefly in a
 * "key not yet attached" state while the operator follows the
 * setup-guide wizard.
 */
data class Repository(
    val id: RepositoryId,
    val name: String,
    val repoUrl: String,
    val defaultBranch: String,
    val vaultKeyPath: String,
    val deployKeyFingerprint: String?,
    val deployKeyAddedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val verification: AccessVerification? = null,
) {
    val isKeyAttached: Boolean get() = deployKeyFingerprint != null
}

/**
 * Cached outcome of the last deploy-key verification probe. Each
 * boolean is three-valued: null = not yet checked / inconclusive,
 * distinct from an explicit false. Mirrored onto the repositories row
 * so the UI renders without a live probe.
 */
data class AccessVerification(
    val read: Boolean?,
    val write: Boolean?,
    val defaultBranchProtected: Boolean?,
    val checkedAt: Instant?,
    val messages: List<String>,
)

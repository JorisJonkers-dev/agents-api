package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant

/**
 * A GitHub repository registered for agent use. Repositories live
 * independently of any Project — many Projects can link the same
 * repository through the project_repositories junction. Access is
 * granted by installing the GitHub App on the repository's owner; the
 * platform stores no per-repo credential.
 */
data class Repository(
    val id: RepositoryId,
    val name: String,
    val repoUrl: String,
    val defaultBranch: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val verification: AccessVerification? = null,
)

/**
 * Cached outcome of the last branch-protection check for the default
 * branch. `defaultBranchProtected` is three-valued: null = not yet
 * checked / inconclusive, distinct from an explicit false. Mirrored
 * onto the repositories row so the UI renders without a live probe.
 */
data class AccessVerification(
    val defaultBranchProtected: Boolean?,
    val checkedAt: Instant?,
    val messages: List<String>,
)

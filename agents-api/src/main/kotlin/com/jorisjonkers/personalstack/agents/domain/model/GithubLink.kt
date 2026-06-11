package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant
import java.util.UUID

@JvmInline
value class GithubLinkId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): GithubLinkId = GithubLinkId(UUID.randomUUID())

        fun parse(s: String): GithubLinkId = GithubLinkId(UUID.fromString(s))
    }
}

/**
 * A linked GitHub repository under a Project. The deploy key for
 * this single repo lives at `vaultKeyPath` (a full
 * `secret/data/agents/projects/<pid>/repos/<rid>` path); the
 * fingerprint is mirrored into the row so the UI can show it
 * without re-reading Vault.
 *
 * `deployKeyFingerprint` is nullable so a row can exist briefly in
 * a "key not yet attached" state while the operator is following
 * the setup guide.
 */
data class GithubLink(
    val id: GithubLinkId,
    val projectId: ProjectId,
    val name: String,
    val repoUrl: String,
    val defaultBranch: String,
    val vaultKeyPath: String,
    val deployKeyFingerprint: String?,
    val deployKeyAddedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isKeyAttached: Boolean get() = deployKeyFingerprint != null
}

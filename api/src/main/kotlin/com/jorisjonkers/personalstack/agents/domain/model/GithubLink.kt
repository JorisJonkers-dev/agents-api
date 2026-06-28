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
 * A legacy, read-only linked GitHub repository under a Project.
 * Superseded by the first-class [Repository] model; retained so
 * workspaces still bound to a link keep working. Access is granted by
 * the installed GitHub App — links no longer carry a deploy key.
 */
data class GithubLink(
    val id: GithubLinkId,
    val projectId: ProjectId,
    val name: String,
    val repoUrl: String,
    val defaultBranch: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant
import java.util.UUID

@JvmInline
value class ProjectId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): ProjectId = ProjectId(UUID.randomUUID())

        fun parse(s: String): ProjectId = ProjectId(UUID.fromString(s))
    }
}

/**
 * A grouping of one or more GitHub repositories. Projects are the
 * stable identity that a deploy key belongs to — even if a repo URL
 * changes (rename, fork), the key stays bound to the project and
 * the row in github_links is updated rather than re-keyed.
 */
data class Project(
    val id: ProjectId,
    val name: String,
    val slug: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

package com.jorisjonkers.personalstack.agents.domain.model

import java.util.UUID

@JvmInline
value class RepositoryId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): RepositoryId = RepositoryId(UUID.randomUUID())

        fun parse(s: String): RepositoryId = RepositoryId(UUID.fromString(s))
    }
}

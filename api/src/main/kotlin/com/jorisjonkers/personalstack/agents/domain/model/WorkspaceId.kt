package com.jorisjonkers.personalstack.agents.domain.model

import java.util.UUID

@JvmInline
value class WorkspaceId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    fun short(): String = value.toString().substring(0, SHORT_ID_LENGTH)

    companion object {
        /**
         * UUID prefix length we use as a stable short identifier
         * for Pod / PVC / Service names. 8 hex chars leaves a 1-in-
         * 4-billion collision space — plenty when there are
         * typically fewer than 50 simultaneous workspaces.
         */
        private const val SHORT_ID_LENGTH = 8

        fun random(): WorkspaceId = WorkspaceId(UUID.randomUUID())

        fun parse(s: String): WorkspaceId = WorkspaceId(UUID.fromString(s))
    }
}

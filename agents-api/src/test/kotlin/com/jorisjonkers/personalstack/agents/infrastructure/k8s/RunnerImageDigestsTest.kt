package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RunnerImageDigestsTest {
    @Test
    fun `parse extracts the digest after the @ separator`() {
        val digest = "sha256:5ef25c126d10f76c3f861c54d72ce377add3d57f2bf23a096868d07dcec20372"
        assertEquals(digest, RunnerImageDigests.parse("ghcr.io/extratoast/agents/agent-runner@$digest"))
    }

    @Test
    fun `parse returns null for a tag-only or blank or null imageID`() {
        assertNull(RunnerImageDigests.parse("ghcr.io/extratoast/agents/agent-runner:latest"))
        assertNull(RunnerImageDigests.parse(""))
        assertNull(RunnerImageDigests.parse(null))
    }

    @Test
    fun `freshest returns the digest of the most recently started observation`() {
        val observed =
            listOf(
                "2026-06-23T10:00:00Z" to "sha256:old",
                "2026-06-23T14:30:00Z" to "sha256:new",
                "2026-06-23T12:00:00Z" to "sha256:mid",
            )
        assertEquals("sha256:new", RunnerImageDigests.freshest(observed))
    }

    @Test
    fun `freshest ignores observations without a digest`() {
        val observed =
            listOf(
                "2026-06-23T16:00:00Z" to null,
                "2026-06-23T09:00:00Z" to "sha256:only",
            )
        assertEquals("sha256:only", RunnerImageDigests.freshest(observed))
    }

    @Test
    fun `freshest returns null when no observation has a digest`() {
        assertNull(RunnerImageDigests.freshest(listOf("2026-06-23T16:00:00Z" to null)))
        assertNull(RunnerImageDigests.freshest(emptyList()))
    }
}

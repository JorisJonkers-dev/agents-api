package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RunnerImageVersionsTest {
    @Test
    fun `tagOf extracts the tag after the last colon`() {
        assertEquals(
            "v0.12.0",
            RunnerImageVersions.tagOf("ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:v0.12.0"),
        )
        assertEquals("latest", RunnerImageVersions.tagOf("ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:latest"))
    }

    @Test
    fun `tagOf returns null for an untagged, digest-pinned, blank, or null reference`() {
        assertNull(RunnerImageVersions.tagOf("ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner"))
        assertNull(RunnerImageVersions.tagOf("ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner@sha256:abc"))
        assertNull(RunnerImageVersions.tagOf(""))
        assertNull(RunnerImageVersions.tagOf(null))
    }

    @Test
    fun `pin replaces the configured tag with the release version`() {
        assertEquals(
            "ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:v0.12.0",
            RunnerImageVersions.pin("ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:latest", "v0.12.0"),
        )
        assertEquals(
            "ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:v0.12.0",
            RunnerImageVersions.pin("ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner", "v0.12.0"),
        )
    }

    @Test
    fun `pin leaves the reference unchanged when no version or already digest-pinned`() {
        val latest = "ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:latest"
        assertEquals(latest, RunnerImageVersions.pin(latest, null))
        assertEquals(latest, RunnerImageVersions.pin(latest, ""))
        val pinned = "ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner@sha256:abc"
        assertEquals(pinned, RunnerImageVersions.pin(pinned, "v0.12.0"))
    }

    @Test
    fun `pin then tagOf round-trips the version`() {
        val image = RunnerImageVersions.pin("ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:latest", "v0.13.1")
        assertEquals("v0.13.1", RunnerImageVersions.tagOf(image))
    }
}

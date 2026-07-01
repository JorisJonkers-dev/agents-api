package com.jorisjonkers.personalstack.agents.application.idle

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WorkspaceActivityTrackerTest {
    private class FixedClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    @Test
    fun `touch records the current instant`() {
        val clock = FixedClock(Instant.parse("2026-05-19T10:00:00Z"))
        val tracker = WorkspaceActivityTracker(clock)
        val id = WorkspaceId.random()
        tracker.touch(id)
        assertThat(tracker.lastSeen(id)).isEqualTo(clock.now)
    }

    @Test
    fun `touch updates an existing entry to the newer instant`() {
        val clock = FixedClock(Instant.parse("2026-05-19T10:00:00Z"))
        val tracker = WorkspaceActivityTracker(clock)
        val id = WorkspaceId.random()
        tracker.touch(id)
        clock.now = clock.now.plusSeconds(60)
        tracker.touch(id)
        assertThat(tracker.lastSeen(id)).isEqualTo(clock.now)
    }

    @Test
    fun `forget removes the entry`() {
        val clock = FixedClock(Instant.parse("2026-05-19T10:00:00Z"))
        val tracker = WorkspaceActivityTracker(clock)
        val id = WorkspaceId.random()
        tracker.touch(id)
        tracker.forget(id)
        assertThat(tracker.lastSeen(id)).isNull()
    }

    @Test
    fun `lastSeen for unknown id returns null`() {
        val tracker = WorkspaceActivityTracker()
        assertThat(tracker.lastSeen(WorkspaceId.random())).isNull()
    }
}

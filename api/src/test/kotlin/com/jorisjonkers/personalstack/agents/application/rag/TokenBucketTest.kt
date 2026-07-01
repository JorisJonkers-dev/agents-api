package com.jorisjonkers.personalstack.agents.application.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class TokenBucketTest {
    private class FixedClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    @Test
    fun `tryAcquire drains the bucket and rejects when empty`() {
        val clock = FixedClock(Instant.parse("2026-05-19T10:00:00Z"))
        val bucket = TokenBucket(capacity = 2, refillInterval = Duration.ofMinutes(15), clock = clock)
        assertThat(bucket.tryAcquire("a")).isTrue
        assertThat(bucket.tryAcquire("a")).isTrue
        assertThat(bucket.tryAcquire("a")).isFalse
    }

    @Test
    fun `buckets are independent per key`() {
        val clock = FixedClock(Instant.parse("2026-05-19T10:00:00Z"))
        val bucket = TokenBucket(capacity = 1, refillInterval = Duration.ofMinutes(15), clock = clock)
        assertThat(bucket.tryAcquire("a")).isTrue
        assertThat(bucket.tryAcquire("a")).isFalse
        assertThat(bucket.tryAcquire("b")).isTrue
    }

    @Test
    fun `refill restores one permit per interval up to capacity`() {
        val clock = FixedClock(Instant.parse("2026-05-19T10:00:00Z"))
        val bucket = TokenBucket(capacity = 2, refillInterval = Duration.ofMinutes(15), clock = clock)
        assertThat(bucket.tryAcquire("a")).isTrue
        assertThat(bucket.tryAcquire("a")).isTrue
        assertThat(bucket.tryAcquire("a")).isFalse
        clock.now = clock.now.plus(Duration.ofMinutes(15))
        assertThat(bucket.tryAcquire("a")).isTrue
        clock.now = clock.now.plus(Duration.ofMinutes(45)) // 3 intervals; capped at capacity
        assertThat(bucket.snapshot("a")).isLessThanOrEqualTo(2)
        assertThat(bucket.tryAcquire("a")).isTrue
        assertThat(bucket.tryAcquire("a")).isTrue
        assertThat(bucket.tryAcquire("a")).isFalse
    }
}

package com.jorisjonkers.personalstack.agents.application.rag

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-key token bucket. Hands out at most `capacity` permits and
 * regenerates one every `refillInterval`. Used to cap auto-capture
 * volume per agent session — the KB enrichment plan called out
 * runaway captures as the failure mode worth designing against.
 *
 * No external deps because the semantics needed are trivial and
 * pulling in Resilience4j or Guava for one bucket-per-session would
 * be heavier than the implementation.
 */
class TokenBucket(
    private val capacity: Int,
    private val refillInterval: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class State(
        var tokens: Int,
        var lastRefill: Instant,
    )

    private val state = ConcurrentHashMap<String, State>()

    /** True if a permit was available and consumed; false otherwise. */
    fun tryAcquire(key: String): Boolean {
        val now = clock.instant()
        val s = state.computeIfAbsent(key) { State(capacity, now) }
        synchronized(s) {
            val elapsed = Duration.between(s.lastRefill, now)
            if (elapsed >= refillInterval) {
                val refills = (elapsed.toMillis() / refillInterval.toMillis()).toInt()
                s.tokens = (s.tokens + refills).coerceAtMost(capacity)
                s.lastRefill = s.lastRefill.plus(refillInterval.multipliedBy(refills.toLong()))
            }
            if (s.tokens <= 0) return false
            s.tokens -= 1
            return true
        }
    }

    fun snapshot(key: String): Int = state[key]?.tokens ?: capacity
}

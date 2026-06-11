package com.jorisjonkers.personalstack.agents.application.idle

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory "last seen" tracker for workspace activity. Anything
 * that proves a workspace is live — a WS attach, a Turn save, a
 * user-input REST call — records here. The IdleScaleDownScheduler
 * then compares the timestamps against an `idleAfter` threshold
 * and tears down workspaces whose last-seen is older than that.
 *
 * Lives in `application/idle/` rather than `infrastructure/` because
 * the policy ("idle = no signals for N minutes") is a domain rule.
 * The actual signal sources are infrastructure adapters that touch
 * `touch(workspaceId)` as they observe activity.
 */
@Component
class WorkspaceActivityTracker(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lastSeen = ConcurrentHashMap<WorkspaceId, Instant>()

    fun touch(workspaceId: WorkspaceId) {
        lastSeen[workspaceId] = clock.instant()
    }

    fun lastSeen(workspaceId: WorkspaceId): Instant? = lastSeen[workspaceId]

    fun forget(workspaceId: WorkspaceId) {
        lastSeen.remove(workspaceId)
    }
}

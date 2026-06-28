package com.jorisjonkers.personalstack.agents.application.idle

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts open browser WebSocket connections per workspace. The idle
 * sweep consults this tracker to guarantee a workspace with a live
 * client is never scaled to zero while the user is watching — even if
 * they are not actively typing (no [WorkspaceActivityTracker.touch]
 * from keystrokes).
 *
 * Attach/detach are called by [com.jorisjonkers.personalstack.agents.infrastructure.ws.SessionAttachHandler]
 * on WebSocket open and close respectively. Thread-safe via atomic
 * reference counting; the count never goes below zero.
 */
@Component
class ConnectedClientTracker {
    private val counts = ConcurrentHashMap<WorkspaceId, AtomicInteger>()

    fun attach(workspaceId: WorkspaceId) {
        counts.computeIfAbsent(workspaceId) { AtomicInteger(0) }.incrementAndGet()
    }

    fun detach(workspaceId: WorkspaceId) {
        counts.computeIfPresent(workspaceId) { _, counter ->
            if (counter.decrementAndGet() <= 0) null else counter
        }
    }

    fun isConnected(workspaceId: WorkspaceId): Boolean = (counts[workspaceId]?.get() ?: 0) > 0
}

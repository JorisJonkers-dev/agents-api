package com.jorisjonkers.personalstack.agents.application.sessionstatus

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor

@Service
class SessionStatusBroadcaster(
    @param:Qualifier("sessionStatusExecutor") private val executor: Executor,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(SessionStatusBroadcaster::class.java)
    private val emitters = ConcurrentHashMap<String, CopyOnWriteArraySet<SseEmitter>>()

    fun subscribe(userId: String): SseEmitter = subscribe(userId, SseEmitter(TIMEOUT_MILLIS))

    internal fun subscribe(
        userId: String,
        emitter: SseEmitter,
    ): SseEmitter {
        require(userId.isNotBlank()) { "userId must not be blank" }
        emitters.computeIfAbsent(userId) { CopyOnWriteArraySet() }.add(emitter)
        emitter.onCompletion { remove(userId, emitter) }
        emitter.onTimeout { remove(userId, emitter) }
        emitter.onError { remove(userId, emitter) }
        executor.execute { send(userId, emitter, KEEPALIVE_EVENT, SessionKeepaliveEvent(clock.instant().toString())) }
        return emitter
    }

    fun broadcastStatus(event: SessionStatusEvent) {
        broadcast(STATUS_EVENT, event)
    }

    fun broadcastRemove(event: SessionRemoveEvent) {
        broadcast(REMOVE_EVENT, event)
    }

    @Scheduled(fixedDelayString = "\${agent-runtime.session-status-keepalive-period-ms:25000}")
    fun sendKeepalive() {
        broadcast(KEEPALIVE_EVENT, SessionKeepaliveEvent(clock.instant().toString()))
    }

    internal fun emitterCount(userId: String): Int = emitters[userId]?.size ?: 0

    private fun broadcast(
        name: String,
        data: Any,
    ) {
        emitters.forEach { (userId, userEmitters) ->
            userEmitters.forEach { emitter ->
                executor.execute {
                    send(userId, emitter, name, data)
                }
            }
        }
    }

    private fun send(
        userId: String,
        emitter: SseEmitter,
        name: String,
        data: Any,
    ) {
        runCatching {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON))
        }.onFailure {
            remove(userId, emitter)
            runCatching { emitter.completeWithError(it) }
            log.debug("removed failed session-status SSE emitter for user {}", userId, it)
        }
    }

    private fun remove(
        userId: String,
        emitter: SseEmitter,
    ) {
        emitters.computeIfPresent(userId) { _, userEmitters ->
            userEmitters.remove(emitter)
            userEmitters.takeIf { it.isNotEmpty() }
        }
    }

    private companion object {
        private const val TIMEOUT_MILLIS = 0L
        private const val STATUS_EVENT = "status"
        private const val REMOVE_EVENT = "remove"
        private const val KEEPALIVE_EVENT = "keepalive"
    }
}

package com.jorisjonkers.personalstack.agents.application.workspacerunner.events

import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerReadinessPublisher
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerReadinessSnapshot
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerReadinessState
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor

@Service
class WorkspaceRunnerEventsBroadcaster(
    @param:Qualifier("sessionStatusExecutor") private val executor: Executor,
    private val clock: Clock = Clock.systemUTC(),
) : RunnerReadinessPublisher {
    private val log = LoggerFactory.getLogger(WorkspaceRunnerEventsBroadcaster::class.java)
    private val emitters = ConcurrentHashMap<WorkspaceId, CopyOnWriteArraySet<SseEmitter>>()

    fun subscribe(workspaceId: WorkspaceId): SseEmitter = subscribe(workspaceId, SseEmitter(TIMEOUT_MILLIS))

    internal fun subscribe(
        workspaceId: WorkspaceId,
        emitter: SseEmitter,
    ): SseEmitter {
        emitters.computeIfAbsent(workspaceId) { CopyOnWriteArraySet() }.add(emitter)
        emitter.onCompletion { remove(workspaceId, emitter) }
        emitter.onTimeout { remove(workspaceId, emitter) }
        emitter.onError { remove(workspaceId, emitter) }
        executor.execute {
            send(workspaceId, emitter, KEEPALIVE_EVENT, RunnerKeepaliveEvent(clock.instant().toString()))
        }
        return emitter
    }

    override fun publish(snapshot: RunnerReadinessSnapshot) {
        val readiness =
            when (snapshot.state) {
                is RunnerReadinessState.Ready -> "ready"
                is RunnerReadinessState.Booting -> "booting"
                is RunnerReadinessState.Unavailable -> "failed"
            }
        val event =
            RunnerReadinessEvent(
                workspaceId = snapshot.workspaceId.toString(),
                readiness = readiness,
                ts = snapshot.checkedAt.toString(),
            )
        val workspaceEmitters = emitters[snapshot.workspaceId] ?: return
        workspaceEmitters.forEach { emitter ->
            executor.execute { send(snapshot.workspaceId, emitter, READINESS_EVENT, event) }
        }
    }

    internal fun emitterCount(workspaceId: WorkspaceId): Int = emitters[workspaceId]?.size ?: 0

    private fun send(
        workspaceId: WorkspaceId,
        emitter: SseEmitter,
        name: String,
        data: Any,
    ) {
        runCatching {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON))
        }.onFailure {
            remove(workspaceId, emitter)
            runCatching { emitter.completeWithError(it) }
            log.debug("removed failed workspace-runner SSE emitter for workspace {}", workspaceId, it)
        }
    }

    private fun remove(
        workspaceId: WorkspaceId,
        emitter: SseEmitter,
    ) {
        emitters.computeIfPresent(workspaceId) { _, wsEmitters ->
            wsEmitters.remove(emitter)
            wsEmitters.takeIf { it.isNotEmpty() }
        }
    }

    private companion object {
        private const val TIMEOUT_MILLIS = 0L
        private const val READINESS_EVENT = "runner-readiness"
        private const val KEEPALIVE_EVENT = "keepalive"
    }
}

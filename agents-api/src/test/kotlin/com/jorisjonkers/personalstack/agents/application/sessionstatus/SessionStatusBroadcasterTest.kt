package com.jorisjonkers.personalstack.agents.application.sessionstatus

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor

class SessionStatusBroadcasterTest {
    private val now = Instant.parse("2026-06-12T09:00:00Z")
    private val broadcaster =
        SessionStatusBroadcaster(
            executor = Executor { it.run() },
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun `subscribe keeps multiple emitters per user and removes completed emitters`() {
        val completed = slot<Runnable>()
        val first = emitter(completion = completed)
        val second = emitter()

        broadcaster.subscribe("user-1", first)
        broadcaster.subscribe("user-1", second)

        assertThat(broadcaster.emitterCount("user-1")).isEqualTo(2)
        completed.captured.run()
        assertThat(broadcaster.emitterCount("user-1")).isEqualTo(1)
    }

    @Test
    fun `broadcast sends events to all registered emitters`() {
        val first = emitter()
        val second = emitter()
        val third = emitter()
        broadcaster.subscribe("user-1", first)
        broadcaster.subscribe("user-1", second)
        broadcaster.subscribe("user-2", third)

        broadcaster.broadcastStatus(SessionStatusEvent("session-1", "RUNNING", idle = false, ts = now.toString()))

        verify { first.send(any<SseEmitter.SseEventBuilder>()) }
        verify { second.send(any<SseEmitter.SseEventBuilder>()) }
        verify { third.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `send failure removes emitter from registry`() {
        val failed = emitter()
        every { failed.send(any<SseEmitter.SseEventBuilder>()) } throws IOException("client disconnected")
        broadcaster.subscribe("user-1", failed)

        broadcaster.broadcastRemove(SessionRemoveEvent("session-1", now.toString()))

        assertThat(broadcaster.emitterCount("user-1")).isEqualTo(0)
        verify { failed.completeWithError(any()) }
    }

    @Test
    fun `subscribe sends immediate keepalive to stabilize the connection`() {
        val emitter = emitter()
        broadcaster.subscribe("user-1", emitter)
        verify { emitter.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `keepalive is sent to connected emitters`() {
        val connected = emitter()
        broadcaster.subscribe("user-1", connected)

        broadcaster.sendKeepalive()

        verify(exactly = 2) { connected.send(any<SseEmitter.SseEventBuilder>()) }
    }

    private fun emitter(completion: io.mockk.CapturingSlot<Runnable>? = null): SseEmitter {
        val emitter = mockk<SseEmitter>(relaxed = true)
        if (completion != null) {
            every { emitter.onCompletion(capture(completion)) } returns Unit
        } else {
            every { emitter.onCompletion(any()) } returns Unit
        }
        every { emitter.onTimeout(any()) } returns Unit
        every { emitter.onError(any()) } returns Unit
        every { emitter.send(any<SseEmitter.SseEventBuilder>()) } returns Unit
        return emitter
    }
}

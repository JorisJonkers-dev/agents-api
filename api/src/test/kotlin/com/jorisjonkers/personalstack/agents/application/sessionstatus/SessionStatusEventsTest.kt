package com.jorisjonkers.personalstack.agents.application.sessionstatus

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionStatusEventsTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `status event has exact minimal shape`() {
        val json =
            objectMapper.readTree(
                objectMapper.writeValueAsString(
                    SessionStatusEvent(
                        sessionId = "session-1",
                        status = "RUNNING",
                        idle = false,
                        ts = "2026-06-12T09:00:00Z",
                    ),
                ),
            )

        assertThat(json.fieldNames().asSequence().toList())
            .containsExactly("sessionId", "status", "idle", "ts")
        assertThat(json["status"].asText()).isEqualTo("RUNNING")
        assertThat(json["idle"].asBoolean()).isFalse
    }

    @Test
    fun `remove event has exact minimal shape`() {
        val json =
            objectMapper.readTree(
                objectMapper.writeValueAsString(
                    SessionRemoveEvent(
                        sessionId = "session-1",
                        ts = "2026-06-12T09:00:00Z",
                    ),
                ),
            )

        assertThat(json.fieldNames().asSequence().toList()).containsExactly("sessionId", "ts")
    }
}

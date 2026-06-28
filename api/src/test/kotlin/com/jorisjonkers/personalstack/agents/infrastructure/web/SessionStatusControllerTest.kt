package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusBroadcaster
import com.jorisjonkers.personalstack.agents.config.XUserIdFilter
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class SessionStatusControllerTest {
    private val broadcaster = mockk<SessionStatusBroadcaster>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(SessionStatusController(broadcaster))
                .setControllerAdvice(GlobalExceptionHandler())
                .addFilters<StandaloneMockMvcBuilder>(XUserIdFilter())
                .build()
    }

    @Test
    fun `GET events returns SSE response headers`() {
        every { broadcaster.subscribe("user-1") } returns SseEmitter()

        mockMvc
            .perform(get("/api/v1/sessions/events").header("X-User-Id", "user-1"))
            .andExpect(status().isOk)
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().string("Cache-Control", "no-cache"))

        verify { broadcaster.subscribe("user-1") }
    }

    @Test
    fun `GET events without X-User-Id returns 401`() {
        mockMvc
            .perform(get("/api/v1/sessions/events"))
            .andExpect(status().isUnauthorized)
    }
}

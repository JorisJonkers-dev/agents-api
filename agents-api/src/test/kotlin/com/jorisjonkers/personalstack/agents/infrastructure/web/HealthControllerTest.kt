package com.jorisjonkers.personalstack.agents.infrastructure.web

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class HealthControllerTest {
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(HealthController())
            .build()

    @Test
    fun `health returns ok with service name`() {
        mockMvc
            .get("/api/v1/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ok") }
                jsonPath("$.service") { value("agents-api") }
            }
    }
}

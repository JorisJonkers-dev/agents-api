package com.jorisjonkers.personalstack.agents.config

import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class InternalBearerAuthFilterTest {
    private fun run(
        configured: String,
        header: String?,
    ): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest("POST", "/api/v1/internal/github/installation-token")
        header?.let { request.addHeader("Authorization", it) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        InternalBearerAuthFilter(configured).doFilter(request, response, chain)
        return response to chain
    }

    @Test
    fun `fail-closed — rejects every request when no bearer is configured`() {
        val (response, chain) = run(configured = "", header = "Bearer anything")
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED)
        assertThat(chain.request).isNull()
    }

    @Test
    fun `rejects a missing Authorization header`() {
        val (response, chain) = run(configured = "s3cret", header = null)
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED)
        assertThat(chain.request).isNull()
    }

    @Test
    fun `rejects a wrong bearer`() {
        val (response, chain) = run(configured = "s3cret", header = "Bearer wrong")
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED)
        assertThat(chain.request).isNull()
    }

    @Test
    fun `passes the chain through for the correct bearer`() {
        val (response, chain) = run(configured = "s3cret", header = "Bearer s3cret")
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
        assertThat(chain.request).isNotNull()
    }
}

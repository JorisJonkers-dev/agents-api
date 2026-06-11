package com.jorisjonkers.personalstack.agents.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class XUserIdFilterTest {
    private val filter = XUserIdFilter()
    private val filterChain =
        FilterChain { _, _ ->
            // no-op
        }

    @Test
    fun `filter passes when X-User-Id header is present`() {
        val request = MockHttpServletRequest("GET", "/api/v1/conversations")
        request.addHeader("X-User-Id", "some-user-id")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
    }

    @Test
    fun `filter returns 401 when X-User-Id header is missing`() {
        val request = MockHttpServletRequest("GET", "/api/v1/conversations")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED)
    }

    @Test
    fun `filter returns 401 when X-User-Id header is blank`() {
        val request = MockHttpServletRequest("GET", "/api/v1/conversations")
        request.addHeader("X-User-Id", "   ")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED)
    }

    @Test
    fun `filter skips health endpoint`() {
        val request = MockHttpServletRequest("GET", "/api/v1/health")
        request.servletPath = "/api/v1/health"
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
    }

    @Test
    fun `filter skips actuator endpoints`() {
        val request = MockHttpServletRequest("GET", "/api/actuator/health")
        request.servletPath = "/api/actuator/health"
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK)
    }
}

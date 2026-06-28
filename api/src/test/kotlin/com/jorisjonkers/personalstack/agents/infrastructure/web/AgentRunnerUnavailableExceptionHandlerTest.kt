package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.context.request.WebRequest

class AgentRunnerUnavailableExceptionHandlerTest {
    private val handler = AgentRunnerUnavailableExceptionHandler()

    private fun webRequest(path: String = "/api/v1/workspaces/abc/sessions"): WebRequest =
        mockk<WebRequest>().also {
            every { it.getDescription(false) } returns "uri=$path"
        }

    @Test
    fun `503 with structured runnerStatus + retryAfterSeconds + Retry-After header`() {
        val workspaceId = WorkspaceId.random()
        val ex =
            AgentRunnerUnavailableException(
                workspaceId = workspaceId,
                runnerStatus = "ConnectionRefused",
                retryAfterSeconds = 5,
            )

        val response = handler.handle(ex, webRequest("/api/v1/workspaces/${workspaceId.value}/sessions"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5")
        val body = response.body!!
        assertThat(body.status).isEqualTo(503)
        assertThat(body.title).isEqualTo("Agent runner not ready")
        assertThat(body.runnerStatus).isEqualTo("ConnectionRefused")
        assertThat(body.retryAfterSeconds).isEqualTo(5)
        assertThat(body.detail).contains(workspaceId.value.toString())
        assertThat(body.detail).contains("ConnectionRefused")
        assertThat(body.detail).contains("Retry in 5s")
    }

    @Test
    fun `503 carries the default retry-after when not explicitly set`() {
        val ex =
            AgentRunnerUnavailableException(
                workspaceId = WorkspaceId.random(),
                runnerStatus = "NotReady",
            )

        val response = handler.handle(ex, webRequest())

        assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER))
            .isEqualTo(AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS.toString())
        assertThat(response.body!!.retryAfterSeconds)
            .isEqualTo(AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS)
    }

    @Test
    fun `instance URI carries the request path`() {
        val workspaceId = WorkspaceId.random()
        val ex = AgentRunnerUnavailableException(workspaceId, "NotReady")

        val response = handler.handle(ex, webRequest("/api/v1/workspaces/$workspaceId/sessions"))

        assertThat(response.body!!.instance.toString()).isEqualTo("/api/v1/workspaces/$workspaceId/sessions")
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.common.web.ProblemDetail
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.net.URI

/**
 * Maps [AgentRunnerUnavailableException] to a **503 Service
 * Unavailable** with a `Retry-After` header and a ProblemDetail
 * payload exposing `runnerStatus` / `retryAfterSeconds` extensions.
 *
 * Sits at `HIGHEST_PRECEDENCE` for the same reason
 * [KubernetesExceptionHandler] does — the generic
 * `Exception → 500` mapper in `GlobalExceptionHandler` should never
 * see this exception type.
 *
 * Visible 5xx instead of a leaky 500 lets the UI render a friendly
 * inline "runner not ready, retry in 5s" message, and lets the
 * Playwright system tests assert on a stable shape.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AgentRunnerUnavailableExceptionHandler {
    private val log = LoggerFactory.getLogger(AgentRunnerUnavailableExceptionHandler::class.java)

    @ExceptionHandler(AgentRunnerUnavailableException::class)
    fun handle(
        ex: AgentRunnerUnavailableException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        val traceId = MDC.get("traceId") ?: MDC.get("trace_id")
        val path = pathOf(request)
        log.warn(
            "agent-runner unavailable traceId={} path={} workspace={} status={} retryAfter={}s",
            traceId,
            path,
            ex.workspaceId.value,
            ex.runnerStatus,
            ex.retryAfterSeconds,
        )
        val body =
            ProblemDetail(
                type = URI.create("https://jorisjonkers.dev/errors/agent-runner-unavailable"),
                title = "Agent runner not ready",
                status = HttpStatus.SERVICE_UNAVAILABLE.value(),
                detail =
                    "Workspace ${ex.workspaceId.value} runner is ${ex.runnerStatus}. " +
                        "Retry in ${ex.retryAfterSeconds}s.",
                instance = path?.let(URI::create),
                traceId = traceId,
                runnerStatus = ex.runnerStatus,
                retryAfterSeconds = ex.retryAfterSeconds,
            )
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds.toString())
            .body(body)
    }

    private fun pathOf(request: WebRequest?): String? =
        when (request) {
            null -> null
            else ->
                runCatching {
                    request
                        .getDescription(false)
                        .removePrefix("uri=")
                        .takeIf { it.isNotBlank() }
                }.getOrNull()
        }
}

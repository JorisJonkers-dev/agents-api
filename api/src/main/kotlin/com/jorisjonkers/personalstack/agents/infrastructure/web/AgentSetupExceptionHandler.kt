package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssue
import com.jorisjonkers.personalstack.common.web.FieldError
import com.jorisjonkers.personalstack.common.web.ProblemDetail
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.net.URI

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AgentSetupExceptionHandler {
    private val log = LoggerFactory.getLogger(AgentSetupExceptionHandler::class.java)

    @ExceptionHandler(AgentSetupValidationException::class)
    fun handle(
        ex: AgentSetupValidationException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        val traceId = MDC.get("traceId") ?: MDC.get("trace_id")
        val path = pathOf(request)
        log.info(
            "agent setup validation rejected traceId={} path={} target={}@{} issueCodes={}",
            traceId,
            path,
            ex.result.target.id.value,
            ex.result.target.version.value,
            ex.result.issues.map { it.code.name },
        )
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(
                ProblemDetail(
                    type = URI.create("https://jorisjonkers.dev/errors/agent-setup-validation"),
                    title = "Agent setup validation failed",
                    status = HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    detail = "Agent setup validation failed.",
                    instance = path?.let(URI::create),
                    errors = ex.result.issues.map { it.redactedFieldError() },
                    traceId = traceId,
                ),
            )
    }

    private fun AgentSetupValidationIssue.redactedFieldError(): FieldError =
        FieldError(
            field = "agentSetup.${code.name}",
            message = redactedMessage(),
            rejectedValue = null,
        )

    private fun AgentSetupValidationIssue.redactedMessage(): String =
        when (binding) {
            null -> code.name
            else -> "${code.name}: ${binding.kind} binding is unavailable"
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

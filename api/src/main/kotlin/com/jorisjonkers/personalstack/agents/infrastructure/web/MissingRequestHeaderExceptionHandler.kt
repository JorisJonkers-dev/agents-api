package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.common.web.ProblemDetail
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.net.URI

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class MissingRequestHeaderExceptionHandler {
    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handle(
        ex: MissingRequestHeaderException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        val path =
            runCatching {
                request?.getDescription(false)?.removePrefix("uri=")?.takeIf { it.isNotBlank() }
            }.getOrNull()
        val body =
            ProblemDetail(
                type = URI.create("https://jorisjonkers.dev/errors/missing-request-header"),
                title = "Missing request header",
                status = HttpStatus.BAD_REQUEST.value(),
                detail = "${ex.headerName} header is required",
                instance = path?.let(URI::create),
                traceId = MDC.get("traceId") ?: MDC.get("trace_id"),
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }
}

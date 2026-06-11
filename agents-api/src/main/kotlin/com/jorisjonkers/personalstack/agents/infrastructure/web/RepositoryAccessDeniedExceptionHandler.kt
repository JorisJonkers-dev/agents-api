package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.exception.RepositoryAccessDeniedException
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

/**
 * Maps [RepositoryAccessDeniedException] to a 422 ProblemDetail so a
 * workspace create that would have left an un-clonable runner fails
 * with an actionable message instead of leaking a 500.
 *
 * Sits at `HIGHEST_PRECEDENCE` so the generic `Exception → 500` mapper
 * never sees this type.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class RepositoryAccessDeniedExceptionHandler {
    private val log = LoggerFactory.getLogger(RepositoryAccessDeniedExceptionHandler::class.java)

    @ExceptionHandler(RepositoryAccessDeniedException::class)
    fun handle(
        ex: RepositoryAccessDeniedException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        val traceId = MDC.get("traceId") ?: MDC.get("trace_id")
        val path =
            runCatching {
                request?.getDescription(false)?.removePrefix("uri=")?.takeIf { it.isNotBlank() }
            }.getOrNull()
        log.warn(
            "repository access denied traceId={} path={} repository={}",
            traceId,
            path,
            ex.repositoryId.value,
        )
        val body =
            ProblemDetail(
                type = URI.create("https://jorisjonkers.dev/errors/repository-access-denied"),
                title = "Deploy key cannot read repository",
                status = HttpStatus.UNPROCESSABLE_ENTITY.value(),
                detail = ex.message,
                instance = path?.let(URI::create),
                traceId = traceId,
            )
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body)
    }
}

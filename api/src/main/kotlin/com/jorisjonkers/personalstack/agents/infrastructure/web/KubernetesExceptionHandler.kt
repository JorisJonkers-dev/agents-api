package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.common.web.FieldError
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import com.jorisjonkers.personalstack.common.web.ProblemDetail
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.client.KubernetesClientException
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
 * Surfaces the upstream Kubernetes API server's verdict instead of
 * the generic 500 the [GlobalExceptionHandler] would otherwise
 * return.
 *
 * The Fabric8 client wraps every non-2xx API server response in
 * [KubernetesClientException] and stamps the parsed `Status` object
 * (when the server returned one) onto the exception. The
 * `kubernetesCode` / `kubernetesReason` extension fields expose the
 * verdict to clients so the UI can distinguish a 403 (RBAC denial)
 * from a 422 (schema validation) without parsing free-form text.
 *
 * `@Order(HIGHEST_PRECEDENCE)` keeps this handler ahead of the
 * generic `Exception` mapper in [GlobalExceptionHandler] so a
 * `KubernetesClientException` always lands here.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class KubernetesExceptionHandler {
    private val log = LoggerFactory.getLogger(KubernetesExceptionHandler::class.java)

    @ExceptionHandler(KubernetesClientException::class)
    fun handleKubernetesClient(
        ex: KubernetesClientException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        val status = ex.status
        val k8sCode = status?.code ?: ex.code.takeIf { it > 0 }
        val k8sReason = status?.reason
        val k8sMessage = status?.message ?: ex.message ?: "no message"
        val traceId = MDC.get("traceId") ?: MDC.get("trace_id")
        val path = pathOf(request)
        log.error(
            "Kubernetes API error traceId={} path={} code={} reason={} message={}",
            traceId,
            path,
            k8sCode,
            k8sReason,
            k8sMessage,
            ex,
        )
        val body =
            ProblemDetail(
                type = URI.create("https://jorisjonkers.dev/errors/kubernetes-api"),
                title = "Kubernetes API Error",
                status = HttpStatus.BAD_GATEWAY.value(),
                detail = buildDetail(k8sCode, k8sReason, k8sMessage),
                instance = path?.let(URI::create),
                errors = fieldErrorsFor(status),
                traceId = traceId,
                exception = ex.javaClass.name,
                kubernetesCode = k8sCode,
                kubernetesReason = k8sReason,
            )
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body)
    }

    private fun buildDetail(
        k8sCode: Int?,
        k8sReason: String?,
        k8sMessage: String,
    ): String =
        buildString {
            append("Kubernetes API request failed")
            when {
                k8sCode != null && k8sReason != null -> append(" (code=$k8sCode, reason=$k8sReason)")
                k8sCode != null -> append(" (code=$k8sCode)")
                k8sReason != null -> append(" (reason=$k8sReason)")
            }
            append(": ")
            append(k8sMessage.takeIf { it.isNotBlank() } ?: "no message from API server")
        }

    /**
     * When the API server returns a structured `causes` list (e.g.
     * CRD admission webhook rejecting a spec field), expose each
     * cause as a `FieldError` so the UI can highlight the offending
     * input without parsing the free-form `detail`.
     */
    private fun fieldErrorsFor(status: Status?): List<FieldError> =
        status
            ?.details
            ?.causes
            ?.map { cause ->
                FieldError(
                    field = cause.field ?: "",
                    message = cause.message ?: cause.reason ?: "Invalid value",
                    rejectedValue = null,
                )
            }.orEmpty()

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

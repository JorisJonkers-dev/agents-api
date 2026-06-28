package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.domain.port.AgentCredentialRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.HttpCredentialWorkerClient
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.CredentialActionResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.CredentialSessionResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.StartCredentialSessionRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.StoredCredentialStatusResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.SubmitRedirectUrlRequest
import com.jorisjonkers.personalstack.common.web.ProblemDetail
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClientResponseException
import java.net.URI

/**
 * Browser-facing proxy onto the internal credential-worker. The whole
 * agents surface already sits behind forward-auth (the AGENTS
 * permission), so this controller adds no auth of its own beyond the
 * standard `X-User-Id` identity the edge injects.
 *
 * Worker 4xx responses (409 busy, 400 bad input, 404 unknown session)
 * are relayed back with the worker's own status as an RFC 7807
 * ProblemDetail whose `detail` carries the worker's message; any other
 * failure falls through to the global handler. The worker emits a bare
 * `{"error":"…"}` body — relaying that verbatim left the browser client
 * (which reads `detail`/`title`/`status`) rendering an opaque
 * "HTTP undefined", hiding the real reason a login could not start.
 */
@RestController
@RequestMapping("/api/v1/credentials")
class CredentialController(
    private val worker: HttpCredentialWorkerClient,
    private val credentials: AgentCredentialRepository,
) {
    // Not injected: the OpenAPI web-mvc slice that exports the spec does not
    // expose an ObjectMapper bean, so a constructor dependency would break it.
    private val objectMapper = ObjectMapper()

    @GetMapping("/status")
    @Operation(summary = "Report what credentials are currently stored for each provider")
    fun storedStatus(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<StoredCredentialStatusResponse> =
        ResponseEntity.ok(StoredCredentialStatusResponse.of(credentials.statusFor(userId)))

    @PostMapping("/sessions")
    @Operation(summary = "Start a CLI re-authentication session for Claude or Codex")
    fun start(
        @RequestHeader("X-User-Id") userId: String,
        @Valid @RequestBody req: StartCredentialSessionRequest,
    ): ResponseEntity<*> =
        relay {
            // updatedBy is the forward-auth identity, never client-chosen.
            val status = worker.start(provider = req.provider, updatedBy = userId)
            ResponseEntity.status(HttpStatus.CREATED).body(CredentialSessionResponse.of(status))
        }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Get the current status of a re-authentication session")
    fun status(
        @PathVariable id: String,
    ): ResponseEntity<*> =
        relay {
            ResponseEntity.ok(CredentialSessionResponse.of(worker.status(id)))
        }

    @PostMapping("/sessions/{id}/redirect")
    @Operation(summary = "Submit the Claude post-approval redirect URL back to a session")
    fun redirect(
        @PathVariable id: String,
        @Valid @RequestBody req: SubmitRedirectUrlRequest,
    ): ResponseEntity<*> =
        relay {
            ResponseEntity.ok(CredentialActionResponse.of(worker.submitRedirect(id, req.url)))
        }

    @PostMapping("/sessions/{id}/cancel")
    @Operation(summary = "Cancel an in-flight re-authentication session")
    fun cancel(
        @PathVariable id: String,
    ): ResponseEntity<*> =
        relay {
            ResponseEntity.ok(CredentialActionResponse.of(worker.cancel(id)))
        }

    private inline fun relay(block: () -> ResponseEntity<*>): ResponseEntity<*> =
        try {
            block()
        } catch (ex: RestClientResponseException) {
            ResponseEntity
                .status(ex.statusCode)
                .body(
                    ProblemDetail(
                        type = URI.create("https://jorisjonkers.dev/errors/credential-worker"),
                        title = "Credential worker request failed",
                        status = ex.statusCode.value(),
                        detail = workerDetail(ex.responseBodyAsString),
                        instance = null,
                    ),
                )
        }

    /** Pull the worker's `{"error":"…"}` message out for the ProblemDetail `detail`. */
    private fun workerDetail(body: String): String =
        runCatching { objectMapper.readTree(body).path("error").asText("") }
            .getOrDefault("")
            .ifBlank { "credential worker request failed" }
}

package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Thin REST adapter for the internal credential-worker
 * ([AgentRuntimeProperties.credentialWorkerUrl]). The worker owns the
 * Claude Code / Codex CLI `/login` lifecycle and the Vault write; this
 * client only relays one HTTP call per verb and presents the shared
 * `x-internal-token` header on each.
 *
 * Worker 4xx/5xx responses surface as [RestClientResponseException] so
 * the controller can map the worker's status (409 busy, 400 bad input,
 * 404 unknown session) onto its own response. The token is never logged.
 */
@Component
class HttpCredentialWorkerClient(
    private val restClient: RestClient,
    private val props: AgentRuntimeProperties,
) {
    /** Worker session-status payload. Mirrors src/worker/session.ts `SessionStatus`. */
    data class SessionStatus(
        val id: String,
        val provider: String,
        val phase: String,
        val authorizeUrl: String? = null,
        val deviceCode: String? = null,
        val verificationUrl: String? = null,
        val needsRedirectUrl: Boolean = false,
        val message: String? = null,
        val error: String? = null,
        val updatedAt: String? = null,
    )

    private data class StartBody(
        val provider: String,
        val updatedBy: String,
    )

    private data class RedirectBody(
        val url: String,
    )

    /** Worker ack for redirect/cancel. */
    data class OkResult(
        val ok: Boolean = false,
        val error: String? = null,
    )

    /** Non-secret summary of one provider's stored credential. */
    data class CredentialStatus(
        val exists: Boolean = false,
        val version: Int = 0,
        val updatedAt: String? = null,
        val updatedBy: String? = null,
        val schemaVersion: String? = null,
    )

    /** Stored-credential status for both providers (worker GET /status). */
    data class StoredStatus(
        val claude: CredentialStatus = CredentialStatus(),
        val codex: CredentialStatus = CredentialStatus(),
    )

    fun start(
        provider: String,
        updatedBy: String,
    ): SessionStatus =
        restClient
            .post()
            .uri("${base()}/sessions")
            .header(INTERNAL_TOKEN_HEADER, props.credentialWorkerToken)
            .body(StartBody(provider = provider, updatedBy = updatedBy))
            .retrieve()
            .body(SessionStatus::class.java)
            ?: error("empty response from credential worker /sessions")

    fun storedStatus(): StoredStatus =
        restClient
            .get()
            .uri("${base()}/status")
            .header(INTERNAL_TOKEN_HEADER, props.credentialWorkerToken)
            .retrieve()
            .body(StoredStatus::class.java)
            ?: error("empty response from credential worker /status")

    fun status(sessionId: String): SessionStatus =
        restClient
            .get()
            .uri("${base()}/sessions/$sessionId")
            .header(INTERNAL_TOKEN_HEADER, props.credentialWorkerToken)
            .retrieve()
            .body(SessionStatus::class.java)
            ?: error("empty response from credential worker /sessions/$sessionId")

    fun submitRedirect(
        sessionId: String,
        url: String,
    ): OkResult =
        restClient
            .post()
            .uri("${base()}/sessions/$sessionId/redirect")
            .header(INTERNAL_TOKEN_HEADER, props.credentialWorkerToken)
            .body(RedirectBody(url = url))
            .retrieve()
            .body(OkResult::class.java)
            ?: error("empty response from credential worker redirect")

    fun cancel(sessionId: String): OkResult =
        restClient
            .post()
            .uri("${base()}/sessions/$sessionId/cancel")
            .header(INTERNAL_TOKEN_HEADER, props.credentialWorkerToken)
            .retrieve()
            .body(OkResult::class.java)
            ?: error("empty response from credential worker cancel")

    private fun base(): String = props.credentialWorkerUrl.trim().trimEnd('/')

    private companion object {
        const val INTERNAL_TOKEN_HEADER = "x-internal-token"
    }
}

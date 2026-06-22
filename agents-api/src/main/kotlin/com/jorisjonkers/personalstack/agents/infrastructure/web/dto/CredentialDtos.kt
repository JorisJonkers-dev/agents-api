package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.infrastructure.integration.HttpCredentialWorkerClient
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/** Start a CLI re-authentication session for one provider. */
data class StartCredentialSessionRequest(
    @field:NotBlank
    @field:Pattern(regexp = "claude|codex", message = "provider must be \"claude\" or \"codex\"")
    val provider: String,
)

/** Submit the Claude post-approval redirect URL back to a session. */
data class SubmitRedirectUrlRequest(
    @field:NotBlank val url: String,
)

/**
 * Credential-worker session status, mirroring the worker's
 * `SessionStatus`. `phase` is one of starting, awaiting_url,
 * awaiting_device, finalizing, succeeded, failed, cancelled. Secret-
 * shaped material is already redacted by the worker.
 */
data class CredentialSessionResponse(
    val id: String,
    val provider: String,
    val phase: String,
    val authorizeUrl: String?,
    val deviceCode: String?,
    val verificationUrl: String?,
    val needsRedirectUrl: Boolean,
    val message: String?,
    val error: String?,
    val updatedAt: String?,
) {
    companion object {
        fun of(s: HttpCredentialWorkerClient.SessionStatus) =
            CredentialSessionResponse(
                id = s.id,
                provider = s.provider,
                phase = s.phase,
                authorizeUrl = s.authorizeUrl,
                deviceCode = s.deviceCode,
                verificationUrl = s.verificationUrl,
                needsRedirectUrl = s.needsRedirectUrl,
                message = s.message,
                error = s.error,
                updatedAt = s.updatedAt,
            )
    }
}

/** Acknowledgement for redirect / cancel relays. */
data class CredentialActionResponse(
    val ok: Boolean,
    val error: String?,
) {
    companion object {
        fun of(r: HttpCredentialWorkerClient.OkResult) = CredentialActionResponse(ok = r.ok, error = r.error)
    }
}

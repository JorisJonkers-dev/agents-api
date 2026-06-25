package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.model.AgentCredentialProvider
import com.jorisjonkers.personalstack.agents.domain.port.AgentCredentialRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.HttpCredentialWorkerClient
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant

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

data class InternalCredentialIngestRequest(
    @field:NotBlank val userId: String,
    @field:NotBlank val provider: String,
    val payload: Map<String, String> = emptyMap(),
    @field:NotBlank val updatedBy: String,
)

data class InternalCredentialIngestResponse(
    val provider: String,
    val status: String,
)

data class StoredCredentialStatusResponse(
    val claude: StoredProviderCredentialStatus,
    val codex: StoredProviderCredentialStatus,
) {
    companion object {
        fun of(statuses: List<AgentCredentialRepository.CredentialStatus>): StoredCredentialStatusResponse {
            val byProvider = statuses.associateBy { it.provider }
            return StoredCredentialStatusResponse(
                claude = StoredProviderCredentialStatus.of(byProvider[AgentCredentialProvider.CLAUDE]),
                codex = StoredProviderCredentialStatus.of(byProvider[AgentCredentialProvider.CODEX]),
            )
        }
    }
}

data class StoredProviderCredentialStatus(
    val exists: Boolean,
    val state: String,
    val valid: Boolean?,
    val validatedAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        fun of(status: AgentCredentialRepository.CredentialStatus?): StoredProviderCredentialStatus {
            if (status == null || !status.stored) {
                return StoredProviderCredentialStatus(
                    exists = false,
                    state = "absent",
                    valid = null,
                    validatedAt = null,
                    updatedAt = null,
                )
            }
            return StoredProviderCredentialStatus(
                exists = true,
                state =
                    when (status.valid) {
                        true -> "usable"
                        false -> "invalid"
                        null -> "unvalidated"
                    },
                valid = status.valid,
                validatedAt = status.validatedAt,
                updatedAt = status.updatedAt,
            )
        }
    }
}

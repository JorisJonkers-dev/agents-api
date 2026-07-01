package com.jorisjonkers.personalstack.agents.infrastructure.credentials

import com.jorisjonkers.personalstack.agents.domain.model.AgentCredentialProvider
import org.springframework.stereotype.Component

enum class CredentialValidationResult {
    VALID,
    EXPLICIT_INVALID,
    UNKNOWN,
}

@Component
class CredentialValidator {
    fun validate(
        provider: AgentCredentialProvider,
        payload: Map<String, String>,
    ): CredentialValidationResult =
        if (payloadMatches(provider, payload)) {
            CredentialValidationResult.UNKNOWN
        } else {
            CredentialValidationResult.EXPLICIT_INVALID
        }

    fun fromHttpStatus(statusCode: Int): CredentialValidationResult =
        when (statusCode) {
            in HTTP_OK_MIN..HTTP_OK_MAX -> CredentialValidationResult.VALID
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> CredentialValidationResult.EXPLICIT_INVALID
            else -> CredentialValidationResult.UNKNOWN
        }

    private fun payloadMatches(
        provider: AgentCredentialProvider,
        payload: Map<String, String>,
    ): Boolean =
        when (provider) {
            AgentCredentialProvider.CLAUDE ->
                payload["credentials_json"].isPresent() || payload["oauth_token"].isPresent()
            AgentCredentialProvider.CODEX -> payload["auth_json"].isPresent()
        }

    private fun String?.isPresent(): Boolean = !isNullOrBlank()

    companion object {
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
    }
}

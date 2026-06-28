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
    @Suppress("UNUSED_PARAMETER")
    fun validate(
        provider: AgentCredentialProvider,
        payload: Map<String, String>,
    ): CredentialValidationResult = CredentialValidationResult.UNKNOWN

    fun fromHttpStatus(statusCode: Int): CredentialValidationResult =
        when (statusCode) {
            in HTTP_OK_MIN..HTTP_OK_MAX -> CredentialValidationResult.VALID
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> CredentialValidationResult.EXPLICIT_INVALID
            else -> CredentialValidationResult.UNKNOWN
        }

    companion object {
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
    }
}

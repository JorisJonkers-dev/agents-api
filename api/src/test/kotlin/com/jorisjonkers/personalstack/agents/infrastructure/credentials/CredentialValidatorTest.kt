package com.jorisjonkers.personalstack.agents.infrastructure.credentials

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CredentialValidatorTest {
    private val validator = CredentialValidator()

    @Test
    fun `http status mapping only treats success and explicit auth failures as conclusive`() {
        assertThat(validator.fromHttpStatus(200)).isEqualTo(CredentialValidationResult.VALID)
        assertThat(validator.fromHttpStatus(204)).isEqualTo(CredentialValidationResult.VALID)
        assertThat(validator.fromHttpStatus(401)).isEqualTo(CredentialValidationResult.EXPLICIT_INVALID)
        assertThat(validator.fromHttpStatus(403)).isEqualTo(CredentialValidationResult.EXPLICIT_INVALID)
        assertThat(validator.fromHttpStatus(400)).isEqualTo(CredentialValidationResult.UNKNOWN)
        assertThat(validator.fromHttpStatus(429)).isEqualTo(CredentialValidationResult.UNKNOWN)
        assertThat(validator.fromHttpStatus(500)).isEqualTo(CredentialValidationResult.UNKNOWN)
    }
}

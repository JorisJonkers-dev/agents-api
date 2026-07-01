package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.domain.model.AgentCredentialProvider
import com.jorisjonkers.personalstack.agents.domain.model.AgentOauthCredential
import com.jorisjonkers.personalstack.agents.domain.port.AgentCredentialRepository
import com.jorisjonkers.personalstack.agents.infrastructure.credentials.CredentialValidationResult
import com.jorisjonkers.personalstack.agents.infrastructure.credentials.CredentialValidator
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.InternalCredentialIngestRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.InternalCredentialIngestResponse
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@Hidden
@RestController
@RequestMapping("/api/v1/internal/credentials")
class InternalCredentialController(
    private val credentials: AgentCredentialRepository,
    private val validator: CredentialValidator,
) {
    @PostMapping
    fun ingest(
        @Valid @RequestBody request: InternalCredentialIngestRequest,
    ): ResponseEntity<InternalCredentialIngestResponse> {
        val provider =
            runCatching { AgentCredentialProvider.valueOf(request.provider) }
                .getOrNull()
                ?: return ResponseEntity.badRequest().build()
        if (!payloadMatches(provider, request.payload)) {
            return ResponseEntity.badRequest().build()
        }

        credentials.upsert(
            AgentOauthCredential(
                userId = request.userId,
                provider = provider,
                payload = request.payload,
                valid = null,
                validatedAt = null,
                updatedAt = Instant.now(),
                // The credential owner is the only legitimate updater on this
                // internal ingest path; a client-supplied updatedBy would let a
                // caller forge authorship, so derive it from the owner identity.
                updatedBy = request.userId,
            ),
        )

        val validation =
            runCatching { validator.validate(provider, request.payload) }
                .getOrDefault(CredentialValidationResult.UNKNOWN)
        when (validation) {
            CredentialValidationResult.VALID -> credentials.markValidity(request.userId, provider, true)
            CredentialValidationResult.EXPLICIT_INVALID -> credentials.markValidity(request.userId, provider, false)
            CredentialValidationResult.UNKNOWN -> Unit
        }

        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(InternalCredentialIngestResponse(provider = provider.name, status = validation.name))
    }

    private fun payloadMatches(
        provider: AgentCredentialProvider,
        payload: Map<String, String>,
    ): Boolean =
        when (provider) {
            AgentCredentialProvider.CLAUDE ->
                payload["credentials_json"].isPresent() || payload["oauth_token"].isPresent()
            // config_toml is optional: `codex login` only writes auth.json, and the
            // runner self-provisions a config.toml when none is injected.
            AgentCredentialProvider.CODEX -> payload["auth_json"].isPresent()
        }

    private fun String?.isPresent(): Boolean = !isNullOrBlank()
}

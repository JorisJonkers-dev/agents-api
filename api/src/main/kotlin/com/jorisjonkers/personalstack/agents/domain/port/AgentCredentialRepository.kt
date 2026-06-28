package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.AgentCredentialProvider
import com.jorisjonkers.personalstack.agents.domain.model.AgentOauthCredential

/**
 * Persistence for per-user agent login credentials, keyed by
 * `(userId, provider)`. Replaces the Vault `agents/{claude,codex}-oauth`
 * paths. Implementations store the payload as-is (it is secret) and never log
 * it.
 */
interface AgentCredentialRepository {
    /** Upsert the captured credential, resetting validity until the next probe. */
    fun upsert(credential: AgentOauthCredential): AgentOauthCredential

    /** The user's credential for a provider, or null when none is stored. */
    fun find(
        userId: String,
        provider: AgentCredentialProvider,
    ): AgentOauthCredential?

    /** Mark the most recent probe result for a stored credential. */
    fun markValidity(
        userId: String,
        provider: AgentCredentialProvider,
        valid: Boolean,
    )

    /** Per-provider presence/validity summary for a user (no payload). */
    fun statusFor(userId: String): List<CredentialStatus>

    data class CredentialStatus(
        val provider: AgentCredentialProvider,
        val stored: Boolean,
        val valid: Boolean?,
        val validatedAt: java.time.Instant?,
        val updatedAt: java.time.Instant?,
    )
}

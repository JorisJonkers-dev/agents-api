package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant

/** Which agent CLI a stored login credential is for. */
enum class AgentCredentialProvider {
    CLAUDE,
    CODEX,
    ;

    companion object {
        fun fromRaw(value: String): AgentCredentialProvider? =
            when (value.trim().lowercase()) {
                "claude" -> CLAUDE
                "codex" -> CODEX
                else -> null
            }
    }
}

/**
 * A user's captured login credential for one provider. `payload` carries the
 * provider-specific fields (Claude: `oauth_token`; Codex: `auth_json` +
 * `config_toml`). `valid` reflects the most recent provider probe, or null
 * when a fresh or inconclusive credential has not been verified.
 *
 * The payload is secret: it is never logged or returned to the browser. Only
 * the per-workspace runner injection reads it.
 */
data class AgentOauthCredential(
    val userId: String,
    val provider: AgentCredentialProvider,
    val payload: Map<String, String>,
    val valid: Boolean?,
    val validatedAt: Instant?,
    val updatedAt: Instant,
    val updatedBy: String,
)

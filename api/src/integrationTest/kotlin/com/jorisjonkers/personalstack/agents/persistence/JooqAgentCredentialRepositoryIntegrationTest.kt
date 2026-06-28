package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.AgentCredentialProvider
import com.jorisjonkers.personalstack.agents.domain.model.AgentOauthCredential
import com.jorisjonkers.personalstack.agents.domain.port.AgentCredentialRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class JooqAgentCredentialRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var store: AgentCredentialRepository

    private fun userId() = "user-${UUID.randomUUID()}"

    @Test
    fun `upsert then find round-trips the payload and resets validity`() {
        val user = userId()
        store.upsert(
            AgentOauthCredential(
                userId = user,
                provider = AgentCredentialProvider.CLAUDE,
                payload = mapOf("oauth_token" to "sk-ant-oat01-abc"),
                valid = true,
                validatedAt = Instant.now(),
                updatedAt = Instant.now(),
                updatedBy = user,
            ),
        )

        val loaded = store.find(user, AgentCredentialProvider.CLAUDE)
        assertThat(loaded).isNotNull
        assertThat(loaded!!.payload["oauth_token"]).isEqualTo("sk-ant-oat01-abc")
        // Upsert resets validity until the next probe.
        assertThat(loaded.valid).isNull()
        assertThat(loaded.validatedAt).isNull()
    }

    @Test
    fun `upsert is per user and provider`() {
        val u1 = userId()
        val u2 = userId()
        store.upsert(cred(u1, AgentCredentialProvider.CLAUDE, "t1"))
        store.upsert(cred(u2, AgentCredentialProvider.CLAUDE, "t2"))
        store.upsert(cred(u1, AgentCredentialProvider.CODEX, "c1"))

        assertThat(store.find(u1, AgentCredentialProvider.CLAUDE)!!.payload["oauth_token"]).isEqualTo("t1")
        assertThat(store.find(u2, AgentCredentialProvider.CLAUDE)!!.payload["oauth_token"]).isEqualTo("t2")
        assertThat(store.find(u1, AgentCredentialProvider.CODEX)!!.payload["oauth_token"]).isEqualTo("c1")
    }

    @Test
    fun `re-upsert overwrites in place (no duplicate rows) and markValidity flips the flag`() {
        val user = userId()
        store.upsert(cred(user, AgentCredentialProvider.CLAUDE, "old"))
        store.upsert(cred(user, AgentCredentialProvider.CLAUDE, "new"))
        assertThat(store.find(user, AgentCredentialProvider.CLAUDE)!!.payload["oauth_token"]).isEqualTo("new")

        store.markValidity(user, AgentCredentialProvider.CLAUDE, true)
        val validated = store.find(user, AgentCredentialProvider.CLAUDE)!!
        assertThat(validated.valid).isTrue()
        assertThat(validated.validatedAt).isNotNull()

        val status = store.statusFor(user)
        assertThat(status).hasSize(2)
        val claude = status.single { it.provider == AgentCredentialProvider.CLAUDE }
        val codex = status.single { it.provider == AgentCredentialProvider.CODEX }
        assertThat(claude.stored).isTrue()
        assertThat(claude.valid).isTrue()
        assertThat(claude.validatedAt).isNotNull()
        assertThat(codex.stored).isFalse()
        assertThat(codex.valid).isNull()
    }

    @Test
    fun `find returns null and status marks both providers absent when nothing stored`() {
        val user = userId()
        val status = store.statusFor(user)

        assertThat(store.find(user, AgentCredentialProvider.CLAUDE)).isNull()
        assertThat(status).hasSize(2)
        assertThat(status.all { !it.stored }).isTrue()
    }

    private fun cred(
        user: String,
        provider: AgentCredentialProvider,
        token: String,
    ) = AgentOauthCredential(
        userId = user,
        provider = provider,
        payload = mapOf("oauth_token" to token),
        valid = null,
        validatedAt = null,
        updatedAt = Instant.now(),
        updatedBy = user,
    )
}

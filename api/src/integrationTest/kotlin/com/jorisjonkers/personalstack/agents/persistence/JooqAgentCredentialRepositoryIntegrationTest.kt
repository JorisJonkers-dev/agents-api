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

class JooqAgentCredentialRepositoryIntegrationTest
    @Autowired
    constructor(
        private val store: AgentCredentialRepository,
    ) : IntegrationTestBase {
        private fun userId() = "user-${UUID.randomUUID()}"

        @Test
        fun upsertThenFindRoundTripsThePayloadAndResetsValidity() {
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

            val loaded = (store.find(user, AgentCredentialProvider.CLAUDE)).required()
            assertThat(loaded.payload["oauth_token"]).isEqualTo("sk-ant-oat01-abc")
            // Upsert resets validity until the next probe.
            assertThat(loaded.valid).isNull()
            assertThat(loaded.validatedAt).isNull()
        }

        @Test
        fun upsertIsPerUserAndProvider() {
            val u1 = userId()
            val u2 = userId()
            store.upsert(cred(u1, AgentCredentialProvider.CLAUDE, "t1"))
            store.upsert(cred(u2, AgentCredentialProvider.CLAUDE, "t2"))
            store.upsert(cred(u1, AgentCredentialProvider.CODEX, "c1"))

            assertThat(store.find(u1, AgentCredentialProvider.CLAUDE).required().payload["oauth_token"]).isEqualTo("t1")
            assertThat(store.find(u2, AgentCredentialProvider.CLAUDE).required().payload["oauth_token"]).isEqualTo("t2")
            assertThat(store.find(u1, AgentCredentialProvider.CODEX).required().payload["oauth_token"]).isEqualTo("c1")
        }

        @Test
        fun reUpsertOverwritesInPlaceNoDuplicateRowsAndMarkValidityFlipsTheFlag() {
            val user = userId()
            store.upsert(cred(user, AgentCredentialProvider.CLAUDE, "old"))
            store.upsert(cred(user, AgentCredentialProvider.CLAUDE, "new"))
            assertThat(
                store.find(user, AgentCredentialProvider.CLAUDE).required().payload["oauth_token"],
            ).isEqualTo("new")

            store.markValidity(user, AgentCredentialProvider.CLAUDE, true)
            val validated = store.find(user, AgentCredentialProvider.CLAUDE).required()
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
        fun findReturnsNullAndStatusMarksBothProvidersAbsentWhenNothingStored() {
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

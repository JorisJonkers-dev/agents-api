package com.jorisjonkers.personalstack.agents.infrastructure.credential

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GitCredentialPeerAuthorizationTest {
    private val allowed = setOf("agent", "10002")

    @Test
    fun `accepts a peer whose resolved name is in the allowed set`() {
        assertThat(GitCredentialPeerAuthorization.isAuthorized("agent", allowed)).isTrue()
        assertThat(GitCredentialPeerAuthorization.isAuthorized("10002", allowed)).isTrue()
    }

    @Test
    fun `rejects the api user`() {
        assertThat(GitCredentialPeerAuthorization.isAuthorized("api", allowed)).isFalse()
    }

    @Test
    fun `rejects root`() {
        assertThat(GitCredentialPeerAuthorization.isAuthorized("root", allowed)).isFalse()
    }

    @Test
    fun `rejects a missing peer name`() {
        assertThat(GitCredentialPeerAuthorization.isAuthorized(null, allowed)).isFalse()
    }

    @Test
    fun `is case-sensitive — no accidental widening`() {
        assertThat(GitCredentialPeerAuthorization.isAuthorized("Agent", allowed)).isFalse()
    }
}

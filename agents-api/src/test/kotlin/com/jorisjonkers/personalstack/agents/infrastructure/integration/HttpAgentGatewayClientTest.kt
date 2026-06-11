package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class HttpAgentGatewayClientTest {
    private fun props(verifyBase: String = "") =
        AgentRuntimeProperties(
            namespace = "agents-system",
            image = "img",
            serviceAccount = "sa",
            claudeCredentialsPvc = "c",
            codexCredentialsPvc = "x",
            githubDeployKeySecret = "k",
            verifyGatewayBaseUrl = verifyBase,
        )

    @Test
    fun `verifyAccess returns null and never touches the gateway when no base URL is configured`() {
        // A strict (non-relaxed) mock fails the test if any RestClient
        // method is invoked, proving the empty-base short-circuit.
        val restClient = mockk<RestClient>()
        val client = HttpAgentGatewayClient(restClient, props(verifyBase = ""))

        assertThat(client.verifyAccess("git@github.com:o/r.git", "main")).isNull()
    }
}

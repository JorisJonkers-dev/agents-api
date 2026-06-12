package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AgentSetupDiffServiceTest {
    private val service = AgentSetupDiffService()

    @Test
    fun `reports changed fields and keeps binding names visible`() {
        val from = definition()
        val to =
            definition(
                version = 2,
                image = "agent:v2",
                githubDeployKeySecret = "deploy-key-v2",
            )

        val diff = service.diff(from, to)

        assertThat(diff.hasChanges).isTrue()
        assertThat(diff.changes.map { it.field }).contains("image", "githubDeployKeySecret")
        assertThat(diff.changes.first { it.field == "githubDeployKeySecret" }).satisfies({ change ->
            assertThat(change.fromValue).isEqualTo("deploy-key")
            assertThat(change.toValue).isEqualTo("deploy-key-v2")
            assertThat(change.redacted).isFalse()
        })
    }

    @Test
    fun `redacts sensitive connector config values`() {
        val from = definition(connectorConfig = mapOf("apiToken" to "old", "profile" to "minimal"))
        val to = definition(version = 2, connectorConfig = mapOf("apiToken" to "new", "profile" to "full"))

        val change = service.diff(from, to).changes.single { it.field == "connectorConfig" }

        assertThat(change.redacted).isTrue()
        assertThat(change.fromValue).contains("apiToken=[redacted]", "profile=minimal")
        assertThat(change.toValue).contains("apiToken=[redacted]", "profile=full")
        assertThat(change.fromValue).doesNotContain("old")
        assertThat(change.toValue).doesNotContain("new")
    }

    private fun definition(
        id: String = "standard",
        version: Long = 1,
        image: String = "agent:v1",
        githubDeployKeySecret: String = "deploy-key",
        connectorConfig: Map<String, String> = emptyMap(),
    ): AgentSetupDefinition {
        val now = Instant.parse("2026-06-12T00:00:00Z")
        return AgentSetupDefinition(
            id = AgentSetupId(id),
            version = AgentSetupVersion(version),
            displayName = "Standard",
            description = null,
            namespace = "agents",
            image = image,
            imagePullPolicy = "IfNotPresent",
            serviceAccount = "agent-runner",
            gatewayPort = 8090,
            claudeCredentialsPvc = "claude-creds",
            codexCredentialsPvc = "codex-creds",
            githubDeployKeySecret = githubDeployKeySecret,
            cliTools = emptyMap(),
            knowledgeBaseUrl = "http://kb",
            knowledgeBearerSecret = "kb-secret",
            knowledgeBearerSecretKey = "token",
            mcpServersConfigMap = "mcp",
            defaultMcpProfile = "minimal",
            connectorConfig = connectorConfig,
            toolProfiles = listOf("minimal"),
            toolAllowlist = emptyList(),
            dockerSocketEnabled = false,
            dockerSocketPath = "/var/run/docker.sock",
            dockerSocketSupplementalGroups = emptyList(),
            nodeSelector = emptyMap(),
            createdAt = now,
            updatedAt = now,
        )
    }
}

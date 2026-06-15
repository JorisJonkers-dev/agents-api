package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.time.Instant

@EnableKubernetesMockClient(crud = false)
class Fabric8RunnerBindingInventoryTest {
    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient

    /**
     * Regression: listing nodes is cluster-scoped and agents-api may
     * lack `nodes:list`. The 403 must not propagate, because the
     * KubernetesExceptionHandler maps any KubernetesClientException to
     * a 502 that breaks setup-options and session launch. The
     * node-selector check is only a preflight hint, so it degrades to
     * "assume satisfiable". Unmatched namespaced GETs return 404 here,
     * which Fabric8 surfaces as a null `get()` (existence = false)
     * without throwing.
     */
    @Test
    fun `node selector degrades to satisfiable when listing nodes is forbidden`() {
        server
            .expect()
            .get()
            .withPath("/api/v1/nodes?labelSelector=capability%3Ddocker")
            .andReturn(
                HttpURLConnection.HTTP_FORBIDDEN,
                StatusBuilder()
                    .withCode(HttpURLConnection.HTTP_FORBIDDEN)
                    .withReason("Forbidden")
                    .withMessage(
                        "nodes is forbidden: User \"system:serviceaccount:agents-system:agents-api\" " +
                            "cannot list resource \"nodes\" in API group \"\" at the cluster scope",
                    ).build(),
            ).always()

        val definition = definition(nodeSelector = mapOf("capability" to "docker"))

        assertThatCode { inventory().inspect(definition, null) }.doesNotThrowAnyException()
        assertThat(inventory().inspect(definition, null).nodeSelector.satisfiable).isTrue()
    }

    @Test
    fun `empty node selector is satisfiable without calling the node API`() {
        val snapshot = inventory().inspect(definition(nodeSelector = emptyMap()), null)

        assertThat(snapshot.nodeSelector.satisfiable).isTrue()
    }

    private fun inventory() = Fabric8RunnerBindingInventory(client)

    private fun definition(nodeSelector: Map<String, String>): AgentSetupDefinition {
        val now = Instant.parse("2026-06-12T00:00:00Z")
        return AgentSetupDefinition(
            id = AgentSetupId("standard"),
            version = AgentSetupVersion.initial(),
            displayName = "Standard",
            description = null,
            namespace = "agents",
            image = "agent:latest",
            imagePullPolicy = "IfNotPresent",
            serviceAccount = "runner",
            gatewayPort = 8090,
            claudeCredentialsPvc = "claude-creds",
            codexCredentialsPvc = "codex-creds",
            githubDeployKeySecret = "deploy-key",
            cliTools = emptyMap(),
            knowledgeBaseUrl = "http://kb",
            knowledgeBearerSecret = "kb-secret",
            knowledgeBearerSecretKey = "token",
            mcpServersConfigMap = "mcp",
            defaultMcpProfile = "minimal",
            connectorConfig = emptyMap(),
            toolProfiles = listOf("minimal"),
            toolAllowlist = emptyList(),
            dockerSocketEnabled = false,
            dockerSocketPath = "/var/run/docker.sock",
            dockerSocketSupplementalGroups = emptyList(),
            nodeSelector = nodeSelector,
            createdAt = now,
            updatedAt = now,
        )
    }
}

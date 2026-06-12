package com.jorisjonkers.personalstack.agents.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * Regression coverage for Spring Boot's relaxed-binding behaviour on
 * `Map<String, String>` properties with slash-containing keys.
 *
 * Production failure (kubectl describe on a Pending agent-runner Pod):
 *
 *     Node-Selectors: agentsnode=enschede-gtx-960m-1
 *
 * The intended selector is `agents/node=enschede-gtx-960m-1`
 * — matching the cluster's node label. Without the bracket escape
 * in `application.yml`, Spring's relaxed binding lowercases the key
 * and strips special chars (`-`, `/`, `.`), reducing
 * `agents/node` to `personalstacknode`. No node carries that
 * label, so every agent-runner Pod sits Pending forever and the
 * connection-refused 503 PR #428 surfaces is the user-visible tail.
 *
 * This test binds two property sources representative of the two
 * shapes a YAML author might write and asserts only the
 * bracket-escaped form preserves the literal key.
 */
class AgentRuntimePropertiesBindingTest {
    private val base =
        mapOf(
            "agent-runtime.namespace" to "agents-system",
            "agent-runtime.image" to "ghcr.io/example/agent-runner:latest",
            "agent-runtime.service-account" to "agent-runner",
            "agent-runtime.claude-credentials-pvc" to "claude-credentials",
            "agent-runtime.codex-credentials-pvc" to "codex-credentials",
            "agent-runtime.github-deploy-key-secret" to "agents-github-deploy-key",
        )

    @Test
    fun `node-selector key surrounded by brackets binds literally — including the slash`() {
        // This is the SHAPE the production application.yml now uses.
        // The test would fail with `personalstacknode` if the
        // bracket-escape were dropped, which is exactly the
        // regression we want pre-flight detection for.
        val props =
            bind(
                base +
                    mapOf("agent-runtime.node-selector.[agents/node]" to "enschede-gtx-960m-1"),
            )

        assertThat(props.nodeSelector).containsExactlyEntriesOf(
            mapOf("agents/node" to "enschede-gtx-960m-1"),
        )
    }

    @Test
    fun `node-selector key without brackets is mangled — documents the trap`() {
        // Same logical key written without the bracket escape: this
        // is the shape that shipped to production and produced the
        // 28-hour Pending Pod. Asserting the mangled output here
        // pins the surprising-but-documented Spring behaviour so a
        // future YAML edit doesn't silently re-introduce the trap.
        val props =
            bind(
                base +
                    mapOf("agent-runtime.node-selector.agents/node" to "enschede-gtx-960m-1"),
            )

        // Slash gets dropped — that's the production-breaking part.
        // (Dashes survive; the trap is specifically the special-char
        // strip, not a wholesale alphanum-only normalisation.)
        assertThat(props.nodeSelector.keys).noneMatch { it.contains("/") }
        assertThat(props.nodeSelector).containsKey("agentsnode")
    }

    @Test
    fun `default mcp profile accepts every declared runner profile`() {
        AgentRuntimeProperties.VALID_MCP_PROFILES.forEach { profile ->
            val props =
                bind(
                    base +
                        mapOf("agent-runtime.default-mcp-profile" to profile),
                )

            assertThat(props.defaultMcpProfile).isEqualTo(profile)
        }
    }

    @Test
    fun `default mcp profile rejects unknown values`() {
        assertThatThrownBy {
            bind(
                base +
                    mapOf("agent-runtime.default-mcp-profile" to "frontned"),
            )
        }.rootCause()
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("agent-runtime.default-mcp-profile")
            .hasMessageContaining("frontned")
    }

    @Test
    fun `docker socket defaults keep testcontainers available in runner pods`() {
        val props = bind(base)

        assertThat(props.dockerSocketEnabled).isTrue()
        assertThat(props.dockerSocketPath).isEqualTo("/var/run/docker.sock")
        assertThat(props.dockerSocketSupplementalGroups).containsExactly(131L)
        assertThat(props.nodeSelector).containsEntry("agents/capability-docker-socket", "true")
    }

    @Test
    fun `docker socket supplemental groups bind from comma separated environment value`() {
        val props =
            bind(
                base +
                    mapOf(
                        "agent-runtime.docker-socket-supplemental-groups" to "44,45",
                    ),
            )

        assertThat(props.dockerSocketSupplementalGroups).containsExactly(44L, 45L)
    }

    @Test
    fun `durable session retention and cleanup batch bind from runtime properties`() {
        val props =
            bind(
                base +
                    mapOf(
                        "agent-runtime.durable-session-retention-seconds" to "3600",
                        "agent-runtime.durable-session-cleanup-batch-size" to "7",
                    ),
            )

        assertThat(props.durableSessionRetentionSeconds).isEqualTo(3600)
        assertThat(props.durableSessionCleanupBatchSize).isEqualTo(7)
    }

    @Test
    fun `durable session retention rejects non-positive values`() {
        assertThatThrownBy {
            bind(
                base +
                    mapOf("agent-runtime.durable-session-retention-seconds" to "0"),
            )
        }.rootCause()
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("agent-runtime.durable-session-retention-seconds")
    }

    private fun bind(properties: Map<String, String>): AgentRuntimeProperties {
        val source = MapConfigurationPropertySource(properties)
        return Binder(source)
            .bind("agent-runtime", AgentRuntimeProperties::class.java)
            .get()
    }
}

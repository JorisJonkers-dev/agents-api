package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.port.AgentSetupRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class JooqAgentSetupRepositoryIntegrationTest
    @Autowired
    constructor(
        private val setups: AgentSetupRepository,
    ) : IntegrationTestBase {
        private companion object {
            const val DOCKER_GROUP_ONE = 44L
            const val DOCKER_GROUP_TWO = 45L
        }

        @Test
        fun configuredDefaultSetupIsSelectableAfterStartupInitializer() {
            val selected = (setups.findDefaultSelectable()).required()

            assertThat(selected.definition.id).isEqualTo(AgentSetupId.default())
            assertThat(selected.availability.selectable).isTrue()
            assertThat(selected.availability.defaultSelectable).isTrue()
        }

        @Test
        fun saveAndFindSetupDefinitionAndAvailability() {
            val definition = definition(AgentSetupId("gpu"), AgentSetupVersion(2))
            val availability =
                AgentSetupAvailability(
                    id = definition.id,
                    version = definition.version,
                    selectable = true,
                    defaultSelectable = false,
                    unavailableReason = null,
                    updatedAt = Instant.now(),
                )

            setups.saveDefinition(definition)
            setups.saveAvailability(availability)

            val loaded = setups.findDefinition(definition.id, definition.version).required()
            val selectable = setups.findSelectable()

            assertThat(loaded.id).isEqualTo(definition.id)
            assertThat(loaded.version).isEqualTo(definition.version)
            assertThat(loaded.cliTools).containsEntry("codex", "gpu")
            assertThat(loaded.toolProfiles).containsExactly("frontend", "code-intel")
            assertThat(loaded.nodeSelector).containsEntry("personal-stack/node", "gpu-node")
            assertThat(selectable.map { it.definition.id }).contains(AgentSetupId("gpu"))
        }

        private fun definition(
            id: AgentSetupId,
            version: AgentSetupVersion,
        ): AgentSetupDefinition {
            val now = Instant.now()
            return AgentSetupDefinition(
                id = id,
                version = version,
                displayName = "GPU runner",
                description = "GPU enabled runner",
                namespace = "agents-system",
                image = "ghcr.io/example/gpu-runner:2026",
                imagePullPolicy = "IfNotPresent",
                serviceAccount = "agent-runner",
                gatewayPort = 8090,
                claudeCredentialsPvc = "claude-credentials",
                codexCredentialsPvc = "codex-credentials",
                githubDeployKeySecret = "agents-github-deploy-key",
                cliTools = mapOf("claude" to "default", "codex" to "gpu"),
                knowledgeBaseUrl = "http://knowledge-api:8080",
                knowledgeBearerSecret = "agents-kb-bearer",
                knowledgeBearerSecretKey = "bearer",
                mcpServersConfigMap = "agents-mcp-servers",
                defaultMcpProfile = "frontend",
                connectorConfig = mapOf("profile" to "gpu"),
                toolProfiles = listOf("frontend", "code-intel"),
                toolAllowlist = listOf("bash", "rg"),
                dockerSocketEnabled = true,
                dockerSocketPath = "/var/run/docker.sock",
                dockerSocketSupplementalGroups = listOf(DOCKER_GROUP_ONE, DOCKER_GROUP_TWO),
                nodeSelector = mapOf("personal-stack/node" to "gpu-node"),
                createdAt = now,
                updatedAt = now,
            )
        }
    }

class JooqAgentSetupSeededImageAlignmentIntegrationTest
    @Autowired
    constructor(
        private val setups: AgentSetupRepository,
        private val props: AgentRuntimeProperties,
    ) : IntegrationTestBase {
        /**
         * Guards against runner-image drift: the V22 migration and
         * application.yml must agree on the image tag for default@3.
         * If they diverge, workspaces will be scheduled with the wrong
         * image.  The test fails whenever a new setup version is added
         * without updating the migration or the yml default.
         */
        @Test
        fun seededDefaultSetupImageMatchesRuntimePropertiesDefault() {
            val defaultEntry = setups.findDefaultSelectable()
            requireNotNull(defaultEntry) { "no default-selectable setup found after migrations" }

            val seededImage = defaultEntry.definition.image
            val configuredImage = props.image

            assertThat(seededImage)
                .describedAs(
                    "seeded default setup image must match agent-runtime.image in application.yml; " +
                        "update V22 (or a new migration) and application.yml together",
                )
                .isEqualTo(configuredImage)
        }

        /**
         * The seeded default@3 row must use personal-stack/(all) node-selector
         * keys, not the old agents/(all) prefix that kept runner Pods Pending.
         */
        @Test
        fun seededDefaultSetupNodeSelectorUsesPersonalStackKeys() {
            val defaultEntry = setups.findDefaultSelectable()
            requireNotNull(defaultEntry) { "no default-selectable setup found after migrations" }

            val nodeSelector = defaultEntry.definition.nodeSelector

            assertThat(nodeSelector.keys)
                .describedAs("node-selector must use personal-stack/* keys, not the old agents/* prefix")
                .allMatch { it.startsWith("personal-stack/") }
            assertThat(nodeSelector)
                .containsKey("personal-stack/node")
        }
    }

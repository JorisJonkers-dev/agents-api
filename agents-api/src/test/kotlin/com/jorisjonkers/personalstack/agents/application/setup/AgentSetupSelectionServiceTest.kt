package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssueCode
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.port.AgentSetupRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class AgentSetupSelectionServiceTest {
    private val setups = mockk<AgentSetupRepository>()
    private val service = AgentSetupSelectionService(setups)

    @Test
    fun `selects default setup when no explicit target is supplied`() {
        val entry = entry(id = "default", defaultSelectable = true)
        every { setups.findDefaultSelectable() } returns entry

        assertThat(service.select(id = null, version = null)).isSameAs(entry)
    }

    @Test
    fun `rejects retired setup targets`() {
        val entry = entry(selectable = false, unavailableReason = "retired")
        every { setups.findDefinition(entry.definition.id, entry.definition.version) } returns entry.definition
        every { setups.findAvailability(entry.definition.id, entry.definition.version) } returns entry.availability

        val thrown =
            assertThrows<AgentSetupValidationException> {
                service.requireSelectable(entry.definition.id, entry.definition.version)
            }

        assertThat(thrown.result.issues).singleElement().satisfies({ issue ->
            assertThat(issue.code).isEqualTo(AgentSetupValidationIssueCode.TARGET_NOT_SELECTABLE)
            assertThat(issue.message).isEqualTo("retired")
        })
    }

    @Test
    fun `rejects unknown explicit setup targets`() {
        val id = AgentSetupId("missing")
        val version = AgentSetupVersion.initial()
        every { setups.findDefinition(id, version) } returns null
        every { setups.findAvailability(id, version) } returns null

        val thrown =
            assertThrows<AgentSetupValidationException> {
                service.requireSelectable(id, version)
            }

        assertThat(thrown.result.issues).singleElement().satisfies({ issue ->
            assertThat(issue.code).isEqualTo(AgentSetupValidationIssueCode.TARGET_NOT_FOUND)
        })
    }

    @Test
    fun `requires id and version together`() {
        assertThrows<IllegalArgumentException> {
            service.select(AgentSetupId.default(), null)
        }
    }

    private fun entry(
        id: String = "standard",
        version: Long = 1,
        selectable: Boolean = true,
        defaultSelectable: Boolean = false,
        unavailableReason: String? = null,
    ): AgentSetupCatalogEntry {
        val setupId = AgentSetupId(id)
        val setupVersion = AgentSetupVersion(version)
        val now = Instant.parse("2026-06-12T00:00:00Z")
        return AgentSetupCatalogEntry(
            definition =
                AgentSetupDefinition(
                    id = setupId,
                    version = setupVersion,
                    displayName = "Standard",
                    description = null,
                    namespace = "agents",
                    image = "agent:latest",
                    imagePullPolicy = "IfNotPresent",
                    serviceAccount = "agent-runner",
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
                    nodeSelector = emptyMap(),
                    createdAt = now,
                    updatedAt = now,
                ),
            availability =
                AgentSetupAvailability(
                    id = setupId,
                    version = setupVersion,
                    selectable = selectable,
                    defaultSelectable = defaultSelectable,
                    unavailableReason = unavailableReason,
                    updatedAt = now,
                ),
        )
    }
}

package com.jorisjonkers.personalstack.agents.application.workspacerunner

import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupSelectionService
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class RunnerSetupTargetResolverTest {
    private val setupSelection = mockk<AgentSetupSelectionService>()
    private val setupValidation = mockk<AgentSetupValidationService>()
    private val resolver = RunnerSetupTargetResolver(setupSelection, setupValidation)

    private val now = Instant.parse("2026-06-17T10:00:00Z")
    private val defaultId = AgentSetupId("default")
    private val defaultVersion = AgentSetupVersion(1L)
    private val otherSetupId = AgentSetupId("other")
    private val otherVersion = AgentSetupVersion(2L)

    private val workspace =
        Workspace(
            id = WorkspaceId.random(),
            name = "test",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.PENDING,
            createdAt = now,
            updatedAt = now,
            currentRunnerSetupId = defaultId,
            currentRunnerSetupVersion = defaultVersion,
        )

    @Test
    fun `uses explicit setup when id and version are provided`() {
        val entry = catalogEntry(otherSetupId, otherVersion)
        every { setupSelection.requireSelectable(otherSetupId, otherVersion) } returns entry
        every { setupValidation.requireValid(any()) } returns
            AgentSetupValidationResult(target = AgentSetupRef(defaultId, defaultVersion), valid = true)

        val target = resolver.resolve(workspace, WorkspaceAgentKind.CLAUDE, otherSetupId, otherVersion)

        assertThat(target.entry.definition.id).isEqualTo(otherSetupId)
        verify { setupSelection.requireSelectable(otherSetupId, otherVersion) }
    }

    @Test
    fun `uses workspace current setup when it is valid`() {
        val entry = catalogEntry(defaultId, defaultVersion)
        every { setupSelection.requireSelectable(defaultId, defaultVersion) } returns entry
        val validRef = AgentSetupRef(defaultId, defaultVersion)
        every { setupValidation.validate(any()) } returns AgentSetupValidationResult(target = validRef, valid = true)

        val target = resolver.resolve(workspace, WorkspaceAgentKind.CLAUDE)

        assertThat(target.entry.definition.id).isEqualTo(defaultId)
    }

    @Test
    fun `falls back to catalog default when current setup validation reports invalid`() {
        val currentEntry = catalogEntry(defaultId, defaultVersion)
        val fallbackEntry = catalogEntry(AgentSetupId("fallback"), AgentSetupVersion(1L))
        every { setupSelection.requireSelectable(defaultId, defaultVersion) } returns currentEntry
        val invalidRef = AgentSetupRef(defaultId, defaultVersion)
        every { setupValidation.validate(any()) } returns AgentSetupValidationResult(target = invalidRef, valid = false)
        every { setupSelection.defaultSelectable() } returns fallbackEntry
        every { setupValidation.requireValid(any()) } returns
            AgentSetupValidationResult(target = AgentSetupRef(defaultId, defaultVersion), valid = true)

        val target = resolver.resolve(workspace, WorkspaceAgentKind.CLAUDE)

        assertThat(target.entry.definition.id).isEqualTo(AgentSetupId("fallback"))
        verify { setupSelection.defaultSelectable() }
    }

    @Test
    fun `falls back to catalog default when current setup selection throws`() {
        val fallbackEntry = catalogEntry(AgentSetupId("fallback"), AgentSetupVersion(1L))
        every { setupSelection.requireSelectable(defaultId, defaultVersion) } throws NoSuchElementException("not found")
        every { setupSelection.defaultSelectable() } returns fallbackEntry
        every { setupValidation.requireValid(any()) } returns
            AgentSetupValidationResult(target = AgentSetupRef(defaultId, defaultVersion), valid = true)

        val target = resolver.resolve(workspace, WorkspaceAgentKind.CLAUDE)

        assertThat(target.entry.definition.id).isEqualTo(AgentSetupId("fallback"))
    }

    @Test
    fun `throws when only setupId is provided without version`() {
        assertThatThrownBy {
            resolver.resolve(workspace, WorkspaceAgentKind.CLAUDE, setupId = otherSetupId, setupVersion = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `throws when only setupVersion is provided without id`() {
        assertThatThrownBy {
            resolver.resolve(workspace, WorkspaceAgentKind.CLAUDE, setupId = null, setupVersion = otherVersion)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `requireValidTarget propagates validation exception`() {
        val entry = catalogEntry(defaultId, defaultVersion)
        val invalidResult = AgentSetupValidationResult(target = AgentSetupRef(defaultId, defaultVersion), valid = false)
        every {
            setupValidation.requireValid(any())
        } throws AgentSetupValidationException(invalidResult)

        assertThatThrownBy {
            resolver.requireValidTarget(workspace, WorkspaceAgentKind.CLAUDE, null, entry)
        }.isInstanceOf(AgentSetupValidationException::class.java)
    }

    // ---- helpers ----

    private fun catalogEntry(
        id: AgentSetupId,
        version: AgentSetupVersion,
    ): AgentSetupCatalogEntry {
        val def = mockk<AgentSetupDefinition>()
        every { def.id } returns id
        every { def.version } returns version
        every { def.displayName } returns "test"
        every { def.description } returns null
        every { def.namespace } returns "agents-system"
        every { def.image } returns "img:latest"
        every { def.imagePullPolicy } returns "Always"
        every { def.serviceAccount } returns "sa"
        every { def.gatewayPort } returns 8090
        every { def.claudeCredentialsPvc } returns "creds"
        every { def.codexCredentialsPvc } returns "creds"
        every { def.githubDeployKeySecret } returns "key"
        every { def.cliTools } returns mapOf("claude" to "default")
        every { def.knowledgeBaseUrl } returns "http://kb"
        every { def.knowledgeBearerSecret } returns "sec"
        every { def.knowledgeBearerSecretKey } returns "bearer"
        every { def.mcpServersConfigMap } returns "cm"
        every { def.defaultMcpProfile } returns "minimal"
        every { def.connectorConfig } returns emptyMap()
        every { def.toolProfiles } returns listOf("minimal")
        every { def.toolAllowlist } returns emptyList()
        every { def.dockerSocketEnabled } returns false
        every { def.dockerSocketPath } returns "/var/run/docker.sock"
        every { def.dockerSocketSupplementalGroups } returns emptyList()
        every { def.nodeSelector } returns emptyMap()
        every { def.createdAt } returns now
        every { def.updatedAt } returns now
        val avail = mockk<AgentSetupAvailability>()
        return AgentSetupCatalogEntry(def, avail)
    }
}

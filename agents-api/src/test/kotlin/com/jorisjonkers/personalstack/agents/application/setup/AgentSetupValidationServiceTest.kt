package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.domain.model.AccessVerification
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupBindingRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssueCode
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentSetupRepository
import com.jorisjonkers.personalstack.agents.domain.port.BindingPresence
import com.jorisjonkers.personalstack.agents.domain.port.DockerSocketPresence
import com.jorisjonkers.personalstack.agents.domain.port.NodeSelectorPresence
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.RunnerBindingInventory
import com.jorisjonkers.personalstack.agents.domain.port.RunnerBindingInventorySnapshot
import com.jorisjonkers.personalstack.agents.domain.port.SecretBindingPresence
import com.jorisjonkers.personalstack.agents.domain.port.SecretKeyPresence
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class AgentSetupValidationServiceTest {
    private val setups = mockk<AgentSetupRepository>()
    private val inventory = mockk<RunnerBindingInventory>()
    private val repositories = mockk<RepositoryRepository>()
    private val projectRepositories = mockk<ProjectRepositoryRepository>()
    private val workspaceRepositories = mockk<WorkspaceRepositoryRepository>()
    private val service =
        AgentSetupValidationService(
            setups = setups,
            bindingInventory = inventory,
            repositories = repositories,
            projectRepositories = projectRepositories,
            workspaceRepositories = workspaceRepositories,
        )

    @Test
    fun `rejects missing target without inspecting bindings`() {
        val request = request()
        every { setups.findDefinition(request.targetId, request.targetVersion) } returns null
        every { setups.findAvailability(request.targetId, request.targetVersion) } returns null

        val result = service.validate(request)

        assertThat(result.valid).isFalse()
        assertThat(result.issues.map { it.code }).containsExactly(AgentSetupValidationIssueCode.TARGET_NOT_FOUND)
        verify(exactly = 0) { inventory.inspect(any(), any()) }
    }

    @Test
    fun `rejects retired setup targets`() {
        val target = entry(selectable = false, unavailableReason = "retired")
        val request = request(target.definition.id, target.definition.version)
        every { setups.findDefinition(request.targetId, request.targetVersion) } returns target.definition
        every { setups.findAvailability(request.targetId, request.targetVersion) } returns target.availability
        every { inventory.inspect(target.definition, request.workspace) } returns okSnapshot()
        every {
            setups.findDefinition(
                request.workspace.currentRunnerSetupId,
                request.workspace.currentRunnerSetupVersion,
            )
        } returns definition()
        every { workspaceRepositories.findAllByWorkspaceId(request.workspace.id) } returns emptyList()

        val result = service.validate(request)

        assertThat(result.valid).isFalse()
        assertThat(result.issues.map { it.code }).contains(AgentSetupValidationIssueCode.TARGET_NOT_SELECTABLE)
    }

    @Test
    fun `validates compatibility and allowlists`() {
        val allowedProjectId = ProjectId.random()
        val target =
            entry(
                definition =
                    definition(
                        connectorConfig =
                            mapOf(
                                "supportedAgentKinds" to "CODEX",
                                "supportedWorkspaceKinds" to "SCRATCH",
                                "allowedProjectIds" to allowedProjectId.value.toString(),
                            ),
                    ),
            )
        val request =
            request(
                workspace = workspace(kind = WorkspaceKind.REPO_BACKED),
                agentKind = WorkspaceAgentKind.CLAUDE,
            )
        every { setups.findDefinition(request.targetId, request.targetVersion) } returns target.definition
        every { setups.findAvailability(request.targetId, request.targetVersion) } returns target.availability
        every { inventory.inspect(target.definition, request.workspace) } returns okSnapshot()
        every {
            setups.findDefinition(
                request.workspace.currentRunnerSetupId,
                request.workspace.currentRunnerSetupVersion,
            )
        } returns definition()
        every { workspaceRepositories.findAllByWorkspaceId(request.workspace.id) } returns emptyList()

        val result = service.validate(request)

        assertThat(result.valid).isFalse()
        assertThat(result.issues.map { it.code }).contains(
            AgentSetupValidationIssueCode.UNSUPPORTED_AGENT_KIND,
            AgentSetupValidationIssueCode.UNSUPPORTED_WORKSPACE_KIND,
            AgentSetupValidationIssueCode.PROJECT_NOT_ALLOWED,
        )
    }

    @Test
    fun `validates repository allowlist deploy key and project binding`() {
        val repositoryId = RepositoryId.random()
        val allowedRepositoryId = RepositoryId.random()
        val projectId = ProjectId.random()
        val target =
            entry(
                definition =
                    definition(
                        connectorConfig =
                            mapOf("allowedRepositoryIds" to allowedRepositoryId.value.toString()),
                    ),
            )
        val request =
            request(
                workspace = workspace(repositoryId = repositoryId, projectId = projectId),
            )
        every { setups.findDefinition(request.targetId, request.targetVersion) } returns target.definition
        every { setups.findAvailability(request.targetId, request.targetVersion) } returns target.availability
        every { inventory.inspect(target.definition, request.workspace) } returns okSnapshot()
        every {
            setups.findDefinition(
                request.workspace.currentRunnerSetupId,
                request.workspace.currentRunnerSetupVersion,
            )
        } returns definition()
        every { workspaceRepositories.findAllByWorkspaceId(request.workspace.id) } returns emptyList()
        every { repositories.findById(repositoryId) } returns repository(repositoryId, keyAttached = false)
        every { projectRepositories.exists(projectId, repositoryId) } returns false

        val result = service.validate(request)

        assertThat(result.valid).isFalse()
        assertThat(result.issues.map { it.code }).contains(
            AgentSetupValidationIssueCode.REPOSITORY_NOT_ALLOWED,
            AgentSetupValidationIssueCode.DEPLOY_KEY_MISSING,
            AgentSetupValidationIssueCode.REPOSITORY_NOT_LINKED_TO_PROJECT,
        )
    }

    @Test
    fun `validates missing resource bindings without secret values`() {
        val target = entry()
        val request = request()
        val secretKeyRef = AgentSetupBindingRef("Secret", "agents", "kb-secret", "token")
        every { setups.findDefinition(request.targetId, request.targetVersion) } returns target.definition
        every { setups.findAvailability(request.targetId, request.targetVersion) } returns target.availability
        every { inventory.inspect(target.definition, request.workspace) } returns
            RunnerBindingInventorySnapshot(
                serviceAccount = BindingPresence(AgentSetupBindingRef("ServiceAccount", "agents", "runner"), false),
                persistentVolumeClaims =
                    listOf(BindingPresence(AgentSetupBindingRef("PersistentVolumeClaim", "agents", "creds"), false)),
                secrets =
                    listOf(
                        SecretBindingPresence(AgentSetupBindingRef("Secret", "agents", "deploy-key"), false),
                        SecretBindingPresence(
                            ref = AgentSetupBindingRef("Secret", "agents", "kb-secret"),
                            exists = true,
                            requiredKeys = listOf(SecretKeyPresence(secretKeyRef, false)),
                        ),
                    ),
                configMaps = listOf(BindingPresence(AgentSetupBindingRef("ConfigMap", "agents", "mcp"), false)),
                nodeSelector = NodeSelectorPresence(mapOf("agents/docker" to "true"), false),
                dockerSocket = DockerSocketPresence(true, "/var/run/docker.sock", false),
            )
        every {
            setups.findDefinition(
                request.workspace.currentRunnerSetupId,
                request.workspace.currentRunnerSetupVersion,
            )
        } returns definition()
        every { workspaceRepositories.findAllByWorkspaceId(request.workspace.id) } returns emptyList()

        val result = service.validate(request)

        assertThat(result.valid).isFalse()
        assertThat(result.issues.map { it.code }).contains(
            AgentSetupValidationIssueCode.SERVICE_ACCOUNT_MISSING,
            AgentSetupValidationIssueCode.PVC_MISSING,
            AgentSetupValidationIssueCode.SECRET_MISSING,
            AgentSetupValidationIssueCode.SECRET_KEY_MISSING,
            AgentSetupValidationIssueCode.CONFIG_MAP_MISSING,
            AgentSetupValidationIssueCode.NODE_SELECTOR_UNSATISFIED,
            AgentSetupValidationIssueCode.DOCKER_SOCKET_UNSATISFIED,
        )
        assertThat(result.message()).contains("Secret agents/kb-secret:token is missing")
        assertThat(result.message()).doesNotContain("secret-value")
    }

    @Test
    fun `marks stale current setup as warning without rejecting target`() {
        val target = entry()
        val request = request()
        every { setups.findDefinition(request.targetId, request.targetVersion) } returns target.definition
        every { setups.findAvailability(request.targetId, request.targetVersion) } returns target.availability
        every { inventory.inspect(target.definition, request.workspace) } returns okSnapshot()
        every {
            setups.findDefinition(
                request.workspace.currentRunnerSetupId,
                request.workspace.currentRunnerSetupVersion,
            )
        } returns null
        every { workspaceRepositories.findAllByWorkspaceId(request.workspace.id) } returns emptyList()

        val result = service.validate(request)

        assertThat(result.valid).isTrue()
        assertThat(result.warnings.map { it.code }).containsExactly(AgentSetupValidationIssueCode.TARGET_STALE)
    }

    @Test
    fun `requireValid throws validation exception for rejected requests`() {
        val request = request()
        every { setups.findDefinition(request.targetId, request.targetVersion) } returns null
        every { setups.findAvailability(request.targetId, request.targetVersion) } returns null

        val thrown = assertThrows<AgentSetupValidationException> { service.requireValid(request) }

        assertThat(thrown.result.valid).isFalse()
    }

    private fun request(
        targetId: AgentSetupId = AgentSetupId("standard"),
        targetVersion: AgentSetupVersion = AgentSetupVersion.initial(),
        workspace: Workspace = workspace(),
        agentKind: WorkspaceAgentKind? = null,
    ): AgentSetupValidationInput =
        AgentSetupValidationInput(
            workspace = workspace,
            targetId = targetId,
            targetVersion = targetVersion,
            agentKind = agentKind,
        )

    private fun okSnapshot(): RunnerBindingInventorySnapshot =
        RunnerBindingInventorySnapshot(
            serviceAccount = BindingPresence(AgentSetupBindingRef("ServiceAccount", "agents", "runner"), true),
            persistentVolumeClaims = emptyList(),
            secrets = emptyList(),
            configMaps = emptyList(),
            nodeSelector = NodeSelectorPresence(emptyMap(), true),
            dockerSocket = DockerSocketPresence(false, "/var/run/docker.sock", true),
        )

    private fun entry(
        definition: AgentSetupDefinition = definition(),
        selectable: Boolean = true,
        unavailableReason: String? = null,
    ): AgentSetupCatalogEntry =
        AgentSetupCatalogEntry(
            definition = definition,
            availability =
                AgentSetupAvailability(
                    id = definition.id,
                    version = definition.version,
                    selectable = selectable,
                    defaultSelectable = false,
                    unavailableReason = unavailableReason,
                    updatedAt = definition.updatedAt,
                ),
        )

    private fun definition(connectorConfig: Map<String, String> = emptyMap()): AgentSetupDefinition {
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

    private fun workspace(
        repositoryId: RepositoryId? = null,
        projectId: ProjectId? = ProjectId.random(),
        kind: WorkspaceKind = WorkspaceKind.REPO_BACKED,
    ): Workspace {
        val now = Instant.parse("2026-06-12T00:00:00Z")
        return Workspace(
            id = WorkspaceId.random(),
            name = "workspace",
            repoUrl = repositoryId?.let { "git@github.com:ExtraToast/example.git" },
            branch = "main",
            podName = "agent-runner",
            pvcName = "workspace-pvc",
            gatewayEndpoint = "http://runner:8090",
            status = WorkspaceStatus.READY,
            createdAt = now,
            updatedAt = now,
            repositoryId = repositoryId,
            projectId = projectId,
            kind = kind,
        )
    }

    private fun repository(
        id: RepositoryId,
        keyAttached: Boolean,
    ): Repository {
        val now = Instant.parse("2026-06-12T00:00:00Z")
        return Repository(
            id = id,
            name = "example",
            repoUrl = "git@github.com:ExtraToast/example.git",
            defaultBranch = "main",
            vaultKeyPath = "secret/data/example",
            deployKeyFingerprint = if (keyAttached) "SHA256:abc" else null,
            deployKeyAddedAt = if (keyAttached) now else null,
            createdAt = now,
            updatedAt = now,
            verification =
                AccessVerification(
                    read = true,
                    write = true,
                    defaultBranchProtected = true,
                    checkedAt = now,
                    messages = emptyList(),
                ),
        )
    }
}

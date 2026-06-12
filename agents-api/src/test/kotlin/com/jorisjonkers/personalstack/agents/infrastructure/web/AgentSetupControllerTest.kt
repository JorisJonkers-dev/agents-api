package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupDiffService
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationInput
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssue
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssueCode
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentSetupRepository
import com.jorisjonkers.personalstack.agents.domain.port.SetupRestartEventRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class AgentSetupControllerTest {
    private val setups = mockk<AgentSetupRepository>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val events = mockk<SetupRestartEventRepository>()
    private val validation = mockk<AgentSetupValidationService>()
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AgentSetupController(
                    setups = setups,
                    workspaces = workspaces,
                    sessions = sessions,
                    events = events,
                    diffService = AgentSetupDiffService(),
                    validation = validation,
                ),
            ).build()

    private val now = Instant.parse("2026-06-12T00:00:00Z")
    private val workspaceId = WorkspaceId.random()
    private val sessionId = WorkspaceAgentSessionId.random()

    @Test
    fun `GET agent-setups lists selectable catalog entries`() {
        every { setups.findSelectable() } returns listOf(entry(id = "default", defaultSelectable = true))

        mockMvc
            .perform(get("/api/v1/agent-setups"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.setups[0].setup.id").value("default"))
            .andExpect(jsonPath("$.setups[0].setup.version").value(1))
            .andExpect(jsonPath("$.setups[0].displayName").value("Default"))
            .andExpect(jsonPath("$.setups[0].defaultSelectable").value(true))
    }

    @Test
    fun `GET agent setup detail redacts sensitive connector config values`() {
        val entry =
            entry(
                connectorConfig =
                    mapOf(
                        "apiToken" to "super-secret-token",
                        "profile" to "minimal",
                    ),
            )
        every { setups.findDefinition(entry.definition.id, entry.definition.version) } returns entry.definition
        every { setups.findAvailability(entry.definition.id, entry.definition.version) } returns entry.availability

        mockMvc
            .perform(get("/api/v1/agent-setups/standard/versions/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connectorConfig.apiToken").value("[redacted]"))
            .andExpect(jsonPath("$.connectorConfig.profile").value("minimal"))
    }

    @Test
    fun `GET session setup returns current pending and latest failed setup`() {
        val session =
            session().copy(
                pendingSetupId = AgentSetupId("target"),
                pendingSetupVersion = AgentSetupVersion(2),
            )
        every { sessions.findById(sessionId) } returns session
        every { events.findAllBySessionId(sessionId) } returns
            listOf(
                event(status = SetupRestartEventStatus.FAILED, toSetupId = "older", updatedAt = now.minusSeconds(30)),
                event(status = SetupRestartEventStatus.FAILED, toSetupId = "newer", updatedAt = now),
            )

        mockMvc
            .perform(get("/api/v1/workspaces/${workspaceId.value}/sessions/${sessionId.value}/setup"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.id").value("default"))
            .andExpect(jsonPath("$.pending.id").value("target"))
            .andExpect(jsonPath("$.failed.setup.id").value("newer"))
    }

    @Test
    fun `GET workspace setup returns runner setup metadata`() {
        every { workspaces.findById(workspaceId) } returns
            workspace()
                .copy(
                    currentRunnerSetupId = AgentSetupId("standard"),
                    currentRunnerSetupVersion = AgentSetupVersion(1),
                    pendingRunnerSetupId = AgentSetupId("gpu"),
                    pendingRunnerSetupVersion = AgentSetupVersion(2),
                    runnerSetupGeneration = 4,
                    runnerSetupOperation = RunnerSetupOperation.RESTARTING,
                    runnerSetupOperationStartedAt = now,
                    runnerSetupOperationUpdatedAt = now,
                )

        mockMvc
            .perform(get("/api/v1/workspaces/${workspaceId.value}/setup"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.id").value("standard"))
            .andExpect(jsonPath("$.pending.id").value("gpu"))
            .andExpect(jsonPath("$.generation").value(4))
            .andExpect(jsonPath("$.operation").value("RESTARTING"))
    }

    @Test
    fun `GET setup-options validates selectable targets for session`() {
        val workspace = workspace()
        val session = session()
        val target = entry(id = "gpu", version = 2)
        every { workspaces.findById(workspaceId) } returns workspace
        every { sessions.findById(sessionId) } returns session
        every { setups.findSelectable() } returns listOf(target)
        every { validation.validate(any()) } answers {
            val request = firstArg<AgentSetupValidationInput>()
            AgentSetupValidationResult(
                target = AgentSetupRef(request.targetId, request.targetVersion),
                valid = true,
            )
        }

        mockMvc
            .perform(get("/api/v1/workspaces/${workspaceId.value}/sessions/${sessionId.value}/setup-options"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.id").value("default"))
            .andExpect(jsonPath("$.options[0].setup.setup.id").value("gpu"))
            .andExpect(jsonPath("$.options[0].validation.valid").value(true))

        verify {
            validation.validate(
                AgentSetupValidationInput(
                    workspace = workspace,
                    targetId = target.definition.id,
                    targetVersion = target.definition.version,
                    session = session,
                ),
            )
        }
    }

    @Test
    fun `GET setup-preview returns diff and validation result`() {
        val workspace = workspace()
        val session = session()
        val current = definition(id = "default", image = "agent:v1")
        val target = definition(id = "gpu", version = 2, image = "agent:v2")
        every { workspaces.findById(workspaceId) } returns workspace
        every { sessions.findById(sessionId) } returns session
        every { setups.findDefinition(session.currentSetupId, session.currentSetupVersion) } returns current
        every { setups.findDefinition(target.id, target.version) } returns target
        every { validation.validate(any()) } returns
            AgentSetupValidationResult(
                target = AgentSetupRef(target.id, target.version),
                valid = false,
                issues =
                    listOf(
                        AgentSetupValidationIssue(
                            code = AgentSetupValidationIssueCode.SECRET_MISSING,
                            message = "Secret agents/kb-secret is missing",
                        ),
                    ),
            )

        mockMvc
            .perform(
                get("/api/v1/workspaces/${workspaceId.value}/sessions/${sessionId.value}/setup-preview")
                    .param("targetSetupId", "gpu")
                    .param("targetSetupVersion", "2"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.target.id").value("gpu"))
            .andExpect(jsonPath("$.diff.changes[0].field").value("image"))
            .andExpect(jsonPath("$.validation.valid").value(false))
            .andExpect(jsonPath("$.validation.issues[0].code").value("SECRET_MISSING"))
    }

    @Test
    fun `GET session setup-transitions returns session history`() {
        val session = session()
        every { sessions.findById(sessionId) } returns session
        every { events.findAllBySessionId(sessionId) } returns listOf(event())

        mockMvc
            .perform(get("/api/v1/workspaces/${workspaceId.value}/sessions/${sessionId.value}/setup-transitions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].to.id").value("target"))
            .andExpect(jsonPath("$.events[0].status").value("REQUESTED"))
    }

    private fun entry(
        id: String = "standard",
        version: Long = 1,
        defaultSelectable: Boolean = false,
        connectorConfig: Map<String, String> = emptyMap(),
    ): AgentSetupCatalogEntry {
        val definition = definition(id = id, version = version, connectorConfig = connectorConfig)
        return AgentSetupCatalogEntry(
            definition = definition,
            availability =
                AgentSetupAvailability(
                    id = definition.id,
                    version = definition.version,
                    selectable = true,
                    defaultSelectable = defaultSelectable,
                    unavailableReason = null,
                    updatedAt = now,
                ),
        )
    }

    private fun definition(
        id: String = "standard",
        version: Long = 1,
        image: String = "agent:v1",
        connectorConfig: Map<String, String> = emptyMap(),
    ) = AgentSetupDefinition(
        id = AgentSetupId(id),
        version = AgentSetupVersion(version),
        displayName = id.replaceFirstChar { it.titlecase() },
        description = null,
        namespace = "agents",
        image = image,
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

    private fun workspace() =
        Workspace(
            id = workspaceId,
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = "pod",
            pvcName = "pvc",
            gatewayEndpoint = "http://runner:8090",
            status = WorkspaceStatus.READY,
            createdAt = now,
            updatedAt = now,
        )

    private fun session() =
        WorkspaceAgentSession(
            id = sessionId,
            workspaceId = workspaceId,
            kind = WorkspaceAgentKind.CLAUDE,
            gatewayAgentId = "agent",
            status = WorkspaceAgentSessionStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
        )

    private fun event(
        status: SetupRestartEventStatus = SetupRestartEventStatus.REQUESTED,
        toSetupId: String = "target",
        updatedAt: Instant = now,
    ) = SetupRestartEvent(
        id = UUID.randomUUID(),
        workspaceId = workspaceId,
        sessionId = sessionId,
        fromSetupId = AgentSetupId("default"),
        fromSetupVersion = AgentSetupVersion(1),
        toSetupId = AgentSetupId(toSetupId),
        toSetupVersion = AgentSetupVersion(2),
        status = status,
        reason = "restart",
        message = null,
        requestedAt = now,
        startedAt = null,
        completedAt = if (status == SetupRestartEventStatus.FAILED) updatedAt else null,
        createdAt = now,
        updatedAt = updatedAt,
    )
}

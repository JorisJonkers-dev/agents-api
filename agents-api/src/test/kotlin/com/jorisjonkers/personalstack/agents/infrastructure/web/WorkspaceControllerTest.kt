package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.application.command.AttachWorkspaceRepositoryCommand
import com.jorisjonkers.personalstack.agents.application.command.CreateWorkspaceCommand
import com.jorisjonkers.personalstack.agents.application.command.DetachWorkspaceRepositoryCommand
import com.jorisjonkers.personalstack.agents.application.query.GetWorkspaceQueryService
import com.jorisjonkers.personalstack.agents.application.query.ListWorkspacesQueryService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerReadinessSnapshot
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerReadinessState
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService.BootOutcome
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService.BootProvisioningOutcome
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.common.command.CommandBus
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

@Suppress("DEPRECATION")
class WorkspaceControllerTest {
    private val commandBus = mockk<CommandBus>(relaxed = true)
    private val listQuery = mockk<ListWorkspacesQueryService>()
    private val getQuery = mockk<GetWorkspaceQueryService>()
    private val lifecycleService = mockk<WorkspaceRunnerLifecycleService>()
    private val objectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = WorkspaceController(commandBus, listQuery, getQuery, lifecycleService)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    private fun workspace(
        id: WorkspaceId = WorkspaceId.random(),
        kind: WorkspaceKind = WorkspaceKind.REPO_BACKED,
        repositoryId: RepositoryId? = null,
    ): Workspace {
        val now = Instant.now()
        return Workspace(
            id = id,
            name = "demo",
            repoUrl = "git@github.com:owner/repo.git",
            branch = "main",
            podName = "pod",
            pvcName = "pvc",
            gatewayEndpoint = "http://endpoint:8090",
            status = WorkspaceStatus.READY,
            createdAt = now,
            updatedAt = now,
            kind = kind,
            repositoryId = repositoryId,
        )
    }

    private fun repository(id: RepositoryId = RepositoryId.random()) =
        Repository(
            id = id,
            name = "agents",
            repoUrl = "git@github.com:owner/agents.git",
            defaultBranch = "main",
            vaultKeyPath = "secret/data/agents/repositories/${id.value}",
            deployKeyFingerprint = null,
            deployKeyAddedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun readySnapshot(workspaceId: WorkspaceId): RunnerReadinessSnapshot =
        RunnerReadinessSnapshot(
            workspaceId = workspaceId,
            setupId = AgentSetupId.default(),
            setupVersion = AgentSetupVersion.initial(),
            state = RunnerReadinessState.Ready,
            checkedAt = Instant.now(),
        )

    private fun bootingSnapshot(workspaceId: WorkspaceId): RunnerReadinessSnapshot =
        RunnerReadinessSnapshot(
            workspaceId = workspaceId,
            setupId = AgentSetupId.default(),
            setupVersion = AgentSetupVersion.initial(),
            state =
                RunnerReadinessState.Booting(
                    leaseId = UUID.randomUUID(),
                    attempt = 1,
                    startedAt = Instant.now(),
                ),
            checkedAt = Instant.now(),
        )

    private fun unavailableSnapshot(
        workspaceId: WorkspaceId,
        reason: RunnerUnavailableReason,
    ): RunnerReadinessSnapshot =
        RunnerReadinessSnapshot(
            workspaceId = workspaceId,
            setupId = AgentSetupId.default(),
            setupVersion = AgentSetupVersion.initial(),
            state = RunnerReadinessState.Unavailable(reason = reason),
            checkedAt = Instant.now(),
        )

    @Test
    fun `POST creates a workspace and returns 201 with the new shape`() {
        val w = workspace(kind = WorkspaceKind.REPO_BACKED, repositoryId = RepositoryId.random())
        every {
            getQuery.getSummary(any())
        } returns w

        mockMvc
            .perform(
                post("/api/v1/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "name" to "demo",
                                "kind" to "REPO_BACKED",
                                "repositoryId" to w.repositoryId?.value.toString(),
                            ),
                        ),
                    ),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.kind").value("REPO_BACKED"))
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.repositories").doesNotExist())

        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `POST creates a workspace with primary and extra repository ids`() {
        val primaryRepositoryId = RepositoryId.random()
        val extraRepositoryId = RepositoryId.random()
        val w = workspace(kind = WorkspaceKind.REPO_BACKED, repositoryId = primaryRepositoryId)
        every { getQuery.getSummary(any()) } returns w

        mockMvc
            .perform(
                post("/api/v1/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "name" to "demo",
                                "kind" to "REPO_BACKED",
                                "primaryRepositoryId" to primaryRepositoryId.value.toString(),
                                "repositoryIds" to
                                    listOf(
                                        extraRepositoryId.value.toString(),
                                        primaryRepositoryId.value.toString(),
                                        extraRepositoryId.value.toString(),
                                    ),
                            ),
                        ),
                    ),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.repositoryId").value(primaryRepositoryId.value.toString()))

        verify {
            commandBus.dispatch(
                match<CreateWorkspaceCommand> {
                    it.repositoryId == primaryRepositoryId &&
                        it.repositoryIds == listOf(primaryRepositoryId, extraRepositoryId)
                },
            )
        }
    }

    @Test
    fun `POST rejects conflicting primary repository fields`() {
        mockMvc
            .perform(
                post("/api/v1/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "name" to "demo",
                                "repositoryId" to UUID.randomUUID().toString(),
                                "primaryRepositoryId" to UUID.randomUUID().toString(),
                            ),
                        ),
                    ),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { commandBus.dispatch(any()) }
    }

    @Test
    fun `POST returns error on blank name`() {
        mockMvc
            .perform(
                post("/api/v1/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("name" to ""))),
            ).andExpect { result ->
                // standaloneSetup doesn't wire the same validation
                // converter the full app uses; either 4xx is acceptable
                // here — the assertion is "the validation kicked in".
                require(result.response.status in 400..499) {
                    "expected client error, got ${result.response.status}"
                }
            }
    }

    @Test
    fun `GET list returns active workspaces`() {
        every { listQuery.listActive() } returns listOf(workspace(), workspace())
        mockMvc
            .perform(get("/api/v1/workspaces"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET by id returns workspace detail with repositories under workspace`() {
        val w = workspace()
        val r = repository()
        every { getQuery.get(w.id) } returns
            GetWorkspaceQueryService.WorkspaceView(
                w,
                emptyList(),
                listOf(
                    GetWorkspaceQueryService.WorkspaceRepositoryView(
                        repository = r,
                        isPrimary = true,
                        attachedAt = Instant.now(),
                    ),
                ),
            )
        every { lifecycleService.runnerImageStatus(w) } returns
            WorkspaceRunnerLifecycleService.RunnerImageStatus(
                version = "v0.12.0",
                upgradeAvailable = true,
            )
        mockMvc
            .perform(get("/api/v1/workspaces/${w.id.value}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workspace.id").value(w.id.value.toString()))
            .andExpect(jsonPath("$.workspace.repositories[0].id").value(r.id.value.toString()))
            .andExpect(jsonPath("$.workspace.repositories[0].name").value("agents"))
            .andExpect(jsonPath("$.workspace.repositories[0].isPrimary").value(true))
            // Runner image surfaced for the operator: release version (v-stripped) + upgrade flag.
            .andExpect(jsonPath("$.workspace.runnerImage.version").value("0.12.0"))
            .andExpect(jsonPath("$.workspace.runnerImage.upgradeAvailable").value(true))
            .andExpect(jsonPath("$.sessions").isArray)
    }

    @Test
    fun `GET by id with non-existent returns 404`() {
        every { getQuery.get(any()) } returns null
        mockMvc
            .perform(get("/api/v1/workspaces/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE destroys workspace and returns 204`() {
        mockMvc
            .perform(delete("/api/v1/workspaces/${UUID.randomUUID()}"))
            .andExpect(status().isNoContent)
        verify { commandBus.dispatch(any()) }
    }

    @Test
    fun `POST repositories attaches repository and returns 204`() {
        val workspaceId = WorkspaceId.random()
        val repositoryId = RepositoryId.random()

        mockMvc
            .perform(
                post("/api/v1/workspaces/${workspaceId.value}/repositories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("repositoryId" to repositoryId.value.toString()))),
            ).andExpect(status().isNoContent)

        verify {
            commandBus.dispatch(
                match<AttachWorkspaceRepositoryCommand> {
                    it.workspaceId == workspaceId && it.repositoryId == repositoryId
                },
            )
        }
    }

    @Test
    fun `POST repositories rejects missing repositoryId`() {
        mockMvc
            .perform(
                post("/api/v1/workspaces/${UUID.randomUUID()}/repositories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(emptyMap<String, String>())),
            ).andExpect { result ->
                require(result.response.status in 400..499) {
                    "expected client error, got ${result.response.status}"
                }
            }

        verify(exactly = 0) { commandBus.dispatch(any()) }
    }

    @Test
    fun `DELETE repositories detaches repository and returns 204`() {
        val workspaceId = WorkspaceId.random()
        val repositoryId = RepositoryId.random()

        mockMvc
            .perform(delete("/api/v1/workspaces/${workspaceId.value}/repositories/${repositoryId.value}"))
            .andExpect(status().isNoContent)

        verify {
            commandBus.dispatch(
                match<DetachWorkspaceRepositoryCommand> {
                    it.workspaceId == workspaceId && it.repositoryId == repositoryId
                },
            )
        }
    }

    @Test
    fun `POST connect returns 200 when runner is already ready`() {
        val w = workspace()
        val snapshot = readySnapshot(w.id)
        every { lifecycleService.boot(w.id, any()) } returns
            BootOutcome.Ready(w, BootProvisioningOutcome.AlreadyReady)
        every { lifecycleService.readinessSnapshot(w) } returns snapshot

        mockMvc
            .perform(post("/api/v1/workspaces/${w.id.value}/connect"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("ready"))
            .andExpect(jsonPath("$.reason").doesNotExist())
    }

    @Test
    fun `POST connect returns 202 when runner was just provisioned`() {
        val w = workspace()
        val snapshot = readySnapshot(w.id)
        every { lifecycleService.boot(w.id, any()) } returns
            BootOutcome.Ready(w, BootProvisioningOutcome.Provisioned("pod", "pvc", "http://pod.svc:8090"))
        every { lifecycleService.readinessSnapshot(w) } returns snapshot

        mockMvc
            .perform(post("/api/v1/workspaces/${w.id.value}/connect"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.state").value("ready"))
    }

    @Test
    fun `POST connect returns 202 when boot lease is held by another caller`() {
        val w = workspace()
        val snapshot = bootingSnapshot(w.id)
        every { lifecycleService.boot(w.id, any()) } returns
            BootOutcome.Conflict(RunnerUnavailableReason.BOOT_LEASE_HELD)
        every { getQuery.getSummary(w.id) } returns w
        every { lifecycleService.readinessSnapshot(w) } returns snapshot

        mockMvc
            .perform(post("/api/v1/workspaces/${w.id.value}/connect"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.state").value("booting"))
    }

    @Test
    fun `POST connect returns 404 when workspace does not exist`() {
        val id = UUID.randomUUID()
        every { lifecycleService.boot(WorkspaceId(id), any()) } returns
            BootOutcome.Conflict(RunnerUnavailableReason.WORKSPACE_NOT_FOUND)

        mockMvc
            .perform(post("/api/v1/workspaces/$id/connect"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST connect returns 503 when provision failed`() {
        val w = workspace()
        val snapshot = unavailableSnapshot(w.id, RunnerUnavailableReason.PROVISION_FAILED)
        every { lifecycleService.boot(w.id, any()) } returns
            BootOutcome.Conflict(RunnerUnavailableReason.PROVISION_FAILED)
        every { getQuery.getSummary(w.id) } returns w
        every { lifecycleService.readinessSnapshot(w) } returns snapshot

        mockMvc
            .perform(post("/api/v1/workspaces/${w.id.value}/connect"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.state").value("unavailable"))
            .andExpect(jsonPath("$.reason").value("provision_failed"))
    }

    @Test
    fun `POST connect returns 503 when setup operation is in progress`() {
        val w = workspace()
        val snapshot = unavailableSnapshot(w.id, RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS)
        every { lifecycleService.boot(w.id, any()) } returns
            BootOutcome.Conflict(RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS)
        every { getQuery.getSummary(w.id) } returns w
        every { lifecycleService.readinessSnapshot(w) } returns snapshot

        mockMvc
            .perform(post("/api/v1/workspaces/${w.id.value}/connect"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.state").value("unavailable"))
            .andExpect(jsonPath("$.reason").value("setup_operation_in_progress"))
    }
}

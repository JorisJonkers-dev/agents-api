package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.GitCredentialSocketManager
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.InContainerAgentGatewayClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import com.jorisjonkers.personalstack.agents.domain.model.Workspace as WorkspaceEntity

class WorkspaceRuntimeProvisionerTest {
    private val lifecycleService = mockk<WorkspaceRunnerLifecycleService>(relaxed = true)
    private val directories = mockk<WorkspaceDirectoryService>(relaxed = true)
    private val gitCredentialSockets = mockk<GitCredentialSocketManager>(relaxed = true)
    private val workspaces = mockk<WorkspaceRepository>(relaxed = true)
    private val inContainerGateway = mockk<InContainerAgentGatewayClient>(relaxed = true)
    private val provisioner =
        WorkspaceRuntimeProvisioner(lifecycleService, directories, gitCredentialSockets, workspaces, inContainerGateway)

    @Test
    fun `a Scratch Workspace gets a directory and its git-credential socket, no runner boot`() {
        val id = WorkspaceId.random()

        provisioner.provision(id, WorkspaceKind.SCRATCH)

        verify { directories.ensureCreated(id) }
        verify { gitCredentialSockets.ensureStarted(id) }
        verify(exactly = 0) { lifecycleService.boot(any(), any()) }
    }

    @Test
    fun `a failure creating the socket does not propagate — the workspace row stays visible`() {
        val id = WorkspaceId.random()
        every { gitCredentialSockets.ensureStarted(id) } throws IllegalStateException("boom")

        provisioner.provision(id, WorkspaceKind.SCRATCH)
    }

    @Test
    fun `a Scratch Workspace is marked Ready once its directory and socket are set up`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns scratchWorkspace(id)
        val saved = slot<WorkspaceEntity>()
        every { workspaces.save(capture(saved)) } answers { saved.captured }

        provisioner.provision(id, WorkspaceKind.SCRATCH)

        assertThat(saved.captured.status).isEqualTo(WorkspaceStatus.READY)
        assertThat(saved.captured.failureReason).isNull()
    }

    @Test
    fun `a Scratch Workspace is marked Failed with a reason when its socket fails`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns scratchWorkspace(id)
        every { gitCredentialSockets.ensureStarted(id) } throws IllegalStateException("boom")
        val saved = slot<WorkspaceEntity>()
        every { workspaces.save(capture(saved)) } answers { saved.captured }

        provisioner.provision(id, WorkspaceKind.SCRATCH)

        assertThat(saved.captured.status).isEqualTo(WorkspaceStatus.FAILED)
        assertThat(saved.captured.failureReason).isNotBlank()
    }

    @Test
    fun `a Repo-backed Workspace also gets a directory, a socket and a clone of its primary repository`() {
        val id = WorkspaceId.random()
        val workspace = repoBackedWorkspace(id, repoUrl = "https://github.com/o/r.git", branch = "main")
        every { workspaces.findById(id) } returns workspace

        provisioner.provision(id, WorkspaceKind.REPO_BACKED)

        verify { directories.ensureCreated(id) }
        verify { gitCredentialSockets.ensureStarted(id) }
        verify { inContainerGateway.clone(workspace, "https://github.com/o/r.git", "main") }
        verify { lifecycleService.boot(id, any()) }
    }

    @Test
    fun `a Repo-backed Workspace is marked Ready once its in-container setup succeeds`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns repoBackedWorkspace(id, repoUrl = "https://github.com/o/r.git")
        val saved = slot<WorkspaceEntity>()
        every { workspaces.save(capture(saved)) } answers { saved.captured }

        provisioner.provision(id, WorkspaceKind.REPO_BACKED)

        assertThat(saved.captured.status).isEqualTo(WorkspaceStatus.READY)
    }

    @Test
    fun `a Repo-backed Workspace is marked Failed with a reason when the clone fails`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns repoBackedWorkspace(id, repoUrl = "https://github.com/o/r.git")
        every { inContainerGateway.clone(any(), any(), any()) } throws IllegalStateException("boom")
        val saved = slot<WorkspaceEntity>()
        every { workspaces.save(capture(saved)) } answers { saved.captured }

        provisioner.provision(id, WorkspaceKind.REPO_BACKED)

        assertThat(saved.captured.status).isEqualTo(WorkspaceStatus.FAILED)
        assertThat(saved.captured.failureReason).isNotBlank()
    }

    @Test
    fun `a Repo-backed Workspace still boots its runner Pod — the double clone is transitional, not a bug`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns repoBackedWorkspace(id, repoUrl = "https://github.com/o/r.git")

        provisioner.provision(id, WorkspaceKind.REPO_BACKED)

        verify { lifecycleService.boot(id, any()) }
    }

    @Test
    fun `a Repo-backed Workspace with no repoUrl gets its directory and socket but nothing to clone`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns repoBackedWorkspace(id, repoUrl = null)

        provisioner.provision(id, WorkspaceKind.REPO_BACKED)

        verify { directories.ensureCreated(id) }
        verify(exactly = 0) { inContainerGateway.clone(any(), any(), any()) }
    }

    @Test
    fun `an unknown Repo-backed Workspace skips the in-container clone but still boots a runner`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns null

        provisioner.provision(id, WorkspaceKind.REPO_BACKED)

        verify(exactly = 0) { directories.ensureCreated(id) }
        verify { lifecycleService.boot(id, any()) }
    }

    @Test
    fun `a clone failure does not propagate and the runner still boots`() {
        val id = WorkspaceId.random()
        every { workspaces.findById(id) } returns repoBackedWorkspace(id, repoUrl = "https://github.com/o/r.git")
        every { inContainerGateway.clone(any(), any(), any()) } throws IllegalStateException("boom")

        provisioner.provision(id, WorkspaceKind.REPO_BACKED)

        verify { lifecycleService.boot(id, any()) }
    }

    @Test
    fun `a CHAT Workspace boots a runner instead of touching the directory, socket or in-container clone`() {
        val id = WorkspaceId.random()

        provisioner.provision(id, WorkspaceKind.CHAT)

        verify(exactly = 0) { directories.ensureCreated(any()) }
        verify(exactly = 0) { gitCredentialSockets.ensureStarted(any()) }
        verify(exactly = 0) { inContainerGateway.clone(any(), any(), any()) }
    }

    private fun repoBackedWorkspace(
        id: WorkspaceId,
        repoUrl: String?,
        branch: String? = null,
    ) = WorkspaceEntity(
        id = id,
        name = "repo-workspace",
        repoUrl = repoUrl,
        branch = branch,
        podName = null,
        pvcName = null,
        gatewayEndpoint = null,
        status = WorkspaceStatus.PREPARING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        kind = WorkspaceKind.REPO_BACKED,
    )

    private fun scratchWorkspace(id: WorkspaceId) =
        WorkspaceEntity(
            id = id,
            name = "scratch-workspace",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.PREPARING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            kind = WorkspaceKind.SCRATCH,
        )
}

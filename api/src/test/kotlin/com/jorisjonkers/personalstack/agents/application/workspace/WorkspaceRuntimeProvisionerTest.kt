package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.GitCredentialSocketManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class WorkspaceRuntimeProvisionerTest {
    private val lifecycleService = mockk<WorkspaceRunnerLifecycleService>(relaxed = true)
    private val directories = mockk<WorkspaceDirectoryService>(relaxed = true)
    private val gitCredentialSockets = mockk<GitCredentialSocketManager>(relaxed = true)
    private val provisioner = WorkspaceRuntimeProvisioner(lifecycleService, directories, gitCredentialSockets)

    @Test
    fun `a Scratch Workspace gets a directory and its git-credential socket, no runner boot`() {
        val id = WorkspaceId.random()

        provisioner.provision(id, WorkspaceKind.SCRATCH)

        verify { directories.ensureCreated(id) }
        verify { gitCredentialSockets.ensureStarted(id) }
        verify(exactly = 0) { lifecycleService.boot(any(), any()) }
    }

    @Test
    fun `a non-Scratch Workspace boots a runner instead of touching the directory or socket`() {
        val id = WorkspaceId.random()

        provisioner.provision(id, WorkspaceKind.REPO_BACKED)

        verify(exactly = 0) { directories.ensureCreated(any()) }
        verify(exactly = 0) { gitCredentialSockets.ensureStarted(any()) }
    }

    @Test
    fun `a failure creating the socket does not propagate — the workspace row stays visible`() {
        val id = WorkspaceId.random()
        every { gitCredentialSockets.ensureStarted(id) } throws IllegalStateException("boom")

        provisioner.provision(id, WorkspaceKind.SCRATCH)
    }
}

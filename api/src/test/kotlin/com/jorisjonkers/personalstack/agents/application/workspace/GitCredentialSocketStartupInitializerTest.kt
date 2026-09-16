package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.GitCredentialSocketManager
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.DefaultApplicationArguments
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class GitCredentialSocketStartupInitializerTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val gitCredentialSockets = mockk<GitCredentialSocketManager>(relaxed = true)

    private fun props(root: Path) =
        AgentRuntimeProperties(
            namespace = "agents-system",
            image = "unused",
            serviceAccount = "unused",
            claudeCredentialsPvc = "unused",
            codexCredentialsPvc = "unused",
            githubDeployKeySecret = "unused",
            workspacesRoot = root.toString(),
        )

    @Test
    fun `does nothing when the workspaces root does not exist — a dev laptop or a test`(
        @TempDir dir: Path,
    ) {
        val missingRoot = dir.resolve("does-not-exist")
        val initializer =
            GitCredentialSocketStartupInitializer(
                props(missingRoot),
                workspaces,
                WorkspaceDirectoryService(props(missingRoot), mockk(relaxed = true)),
                gitCredentialSockets,
            )

        initializer.run(DefaultApplicationArguments())

        verify(exactly = 0) { gitCredentialSockets.ensureStarted(any()) }
    }

    @Test
    fun `starts the socket only for a non-Destroyed workspace whose directory already exists`(
        @TempDir dir: Path,
    ) {
        val hasDir = WorkspaceId.random()
        val noDir = WorkspaceId.random()
        Files.createDirectories(dir.resolve(hasDir.toString()))
        every { workspaces.findAllByStatusNot(WorkspaceStatus.DESTROYED) } returns
            listOf(workspace(hasDir), workspace(noDir))
        val directories = WorkspaceDirectoryService(props(dir), mockk(relaxed = true))
        val initializer =
            GitCredentialSocketStartupInitializer(props(dir), workspaces, directories, gitCredentialSockets)

        initializer.run(DefaultApplicationArguments())

        verify(exactly = 1) { gitCredentialSockets.ensureStarted(hasDir) }
        verify(exactly = 0) { gitCredentialSockets.ensureStarted(noDir) }
    }

    private fun workspace(id: WorkspaceId) =
        Workspace(
            id = id,
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

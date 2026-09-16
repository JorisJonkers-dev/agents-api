package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.infrastructure.process.ProcessRunner
import com.jorisjonkers.personalstack.agents.infrastructure.process.RunAsAgentCommandRunner
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkspaceDirectoryServiceTest {
    private val commands = mockk<RunAsAgentCommandRunner>(relaxed = true)
    private val props =
        AgentRuntimeProperties(
            namespace = "agents-system",
            image = "ghcr.io/example/agent-runner:latest",
            serviceAccount = "agent-runner",
            claudeCredentialsPvc = "claude-credentials",
            codexCredentialsPvc = "codex-credentials",
            githubDeployKeySecret = "agents-github-deploy-key",
            workspacesRoot = "/workspaces",
        )
    private val service = WorkspaceDirectoryService(props, commands)

    @Test
    fun `directoryFor is deterministic from the workspace id`() {
        val id = WorkspaceId.random()

        assertThat(service.directoryFor(id).toString()).isEqualTo("/workspaces/$id")
    }

    @Test
    fun `ensureCreated shells out to mkdir -p as agent, never touching Kubernetes`() {
        val id = WorkspaceId.random()
        val argv = slot<List<String>>()
        every { commands.run(capture(argv), any(), any(), any(), any()) } returns ProcessRunner.Result(0, "", "")

        val dir = service.ensureCreated(id)

        assertThat(dir.toString()).isEqualTo("/workspaces/$id")
        assertThat(argv.captured).containsExactly("mkdir", "-p", "/workspaces/$id")
    }

    @Test
    fun `credentialSocketDirFor is the workspace directory's dot-agents-api subdirectory`() {
        val id = WorkspaceId.random()

        assertThat(service.credentialSocketDirFor(id).toString()).isEqualTo("/workspaces/$id/.agents-api")
    }

    @Test
    fun `ensureCredentialSocketDirCreated creates the workspace dir, then mkdir's and chmod's the socket dir`() {
        val id = WorkspaceId.random()
        every { commands.run(any(), any(), any(), any(), any()) } returns ProcessRunner.Result(0, "", "")

        val dir = service.ensureCredentialSocketDirCreated(id)

        assertThat(dir.toString()).isEqualTo("/workspaces/$id/.agents-api")
        verify { commands.run(listOf("mkdir", "-p", "/workspaces/$id"), any(), any(), any(), any()) }
        verify { commands.run(listOf("mkdir", "-p", "/workspaces/$id/.agents-api"), any(), any(), any(), any()) }
        verify { commands.run(listOf("chmod", "2703", "/workspaces/$id/.agents-api"), any(), any(), any(), any()) }
    }
}

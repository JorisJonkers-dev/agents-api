package com.jorisjonkers.personalstack.agents.application.workspace

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.infrastructure.process.ProcessRunner
import com.jorisjonkers.personalstack.agents.infrastructure.process.RunAsAgentCommandRunner
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
}

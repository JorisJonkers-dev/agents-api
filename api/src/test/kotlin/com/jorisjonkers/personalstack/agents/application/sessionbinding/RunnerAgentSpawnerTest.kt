package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.ResourceAccessException
import java.time.Instant

class RunnerAgentSpawnerTest {
    private val gateway = mockk<AgentGatewayClient>()
    private val spawner = RunnerAgentSpawner(gateway, backoffInitialMs = 0)

    @Test
    fun `spawnWithRetry succeeds on first attempt`() {
        val ws = workspace()
        val session = session(ws.id)
        val expected = gatewayAgent("abc-1")
        every { gateway.spawnAgent(any()) } returns expected

        val result = spawner.spawnWithRetry(ws, session, continuation = null)

        assertThat(result.id).isEqualTo("abc-1")
        verify(exactly = 1) { gateway.spawnAgent(any()) }
    }

    @Test
    fun `spawnWithRetry retries on ResourceAccessException and succeeds`() {
        val ws = workspace()
        val session = session(ws.id)
        val expected = gatewayAgent("abc-2")
        every { gateway.spawnAgent(any()) }
            .throws(ResourceAccessException("refused"))
            .andThen(expected)

        val result = spawner.spawnWithRetry(ws, session, continuation = null)

        assertThat(result.id).isEqualTo("abc-2")
        verify(exactly = 2) { gateway.spawnAgent(any()) }
    }

    @Test
    fun `spawnWithRetry throws AgentRunnerUnavailableException after all attempts fail`() {
        val ws = workspace()
        val session = session(ws.id)
        every { gateway.spawnAgent(any()) } throws ResourceAccessException("refused")

        assertThrows<AgentRunnerUnavailableException> {
            spawner.spawnWithRetry(ws, session, continuation = null)
        }
        verify(exactly = RunnerAgentSpawner.MAX_SPAWN_ATTEMPTS) { gateway.spawnAgent(any()) }
    }

    @Test
    fun `spawnWithRetry forwards epoch and cliSessionId from session`() {
        val ws = workspace()
        val session = session(ws.id, epoch = 3, cliSessionId = "native-42")
        val captured = mutableListOf<AgentGatewayClient.SpawnAgentRequest>()
        every { gateway.spawnAgent(capture(captured)) } returns gatewayAgent("abc-3")

        spawner.spawnWithRetry(ws, session, continuation = null)

        assertThat(captured).hasSize(1)
        assertThat(captured[0].epoch).isEqualTo(3)
        assertThat(captured[0].resumeCliSessionId).isEqualTo("native-42")
    }

    @Test
    fun `spawnWithRetry forwards continuation metadata`() {
        val ws = workspace()
        val session = session(ws.id)
        val continuation = AgentGatewayClient.ContinuationMetadata(reason = "restart", previousEpoch = 2)
        val captured = mutableListOf<AgentGatewayClient.SpawnAgentRequest>()
        every { gateway.spawnAgent(capture(captured)) } returns gatewayAgent("abc-4")

        spawner.spawnWithRetry(ws, session, continuation)

        assertThat(captured[0].continuation?.reason).isEqualTo("restart")
        assertThat(captured[0].continuation?.previousEpoch).isEqualTo(2)
    }

    private fun workspace() =
        Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = "http://runner:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun session(
        workspaceId: WorkspaceId,
        epoch: Long = 1,
        cliSessionId: String? = null,
    ) = WorkspaceAgentSession(
        id = WorkspaceAgentSessionId.random(),
        workspaceId = workspaceId,
        kind = WorkspaceAgentKind.CLAUDE,
        gatewayAgentId = null,
        status = WorkspaceAgentSessionStatus.STARTING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        epoch = epoch,
        cliSessionId = cliSessionId,
    )

    private fun gatewayAgent(id: String) =
        AgentGatewayClient.GatewayAgent(
            id = id,
            kind = WorkspaceAgentKind.CLAUDE,
            cwd = "/workspace",
        )
}

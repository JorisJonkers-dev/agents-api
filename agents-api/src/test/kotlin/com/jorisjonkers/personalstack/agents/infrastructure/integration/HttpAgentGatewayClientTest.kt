package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant

class HttpAgentGatewayClientTest {
    private fun props(verifyBase: String = "") =
        AgentRuntimeProperties(
            namespace = "agents-system",
            image = "img",
            serviceAccount = "sa",
            claudeCredentialsPvc = "c",
            codexCredentialsPvc = "x",
            githubDeployKeySecret = "k",
            verifyGatewayBaseUrl = verifyBase,
        )

    @Test
    fun `verifyAccess returns null and never touches the gateway when no base URL is configured`() {
        // A strict (non-relaxed) mock fails the test if any RestClient
        // method is invoked, proving the empty-base short-circuit.
        val restClient = mockk<RestClient>()
        val client = HttpAgentGatewayClient(restClient, props(verifyBase = ""))

        assertThat(client.verifyAccess("git@github.com:o/r.git", "main")).isNull()
    }

    @Test
    fun `spawnAgent posts durable session metadata and maps response`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentGatewayClient(builder.build(), props())
        val sessionId = WorkspaceAgentSessionId.random()
        val ws = workspace()

        server
            .expect(requestTo("http://runner:8090/agents"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.kind").value("CLAUDE"))
            .andExpect(jsonPath("$.stableSessionId").value(sessionId.value.toString()))
            .andExpect(jsonPath("$.epoch").value(3))
            .andExpect(jsonPath("$.continuation.reason").value("restart"))
            .andExpect(jsonPath("$.continuation.previousEpoch").value(2))
            .andExpect(jsonPath("$.continuation.fromSetupLabel").value("Default runner"))
            .andExpect(jsonPath("$.continuation.toSetupLabel").value("GPU runner"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "id": "abc12345",
                      "kind": "CLAUDE",
                      "cwd": "/workspace",
                      "cliSessionId": "native-1",
                      "stableSessionId": "${sessionId.value}",
                      "epoch": 3,
                      "continuation": {
                        "reason": "restart",
                        "previousEpoch": 2,
                        "fromSetupLabel": "Default runner",
                        "toSetupLabel": "GPU runner"
                      }
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val spawned =
            client.spawnAgent(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                stableSessionId = sessionId,
                epoch = 3,
                continuation =
                    AgentGatewayClient.ContinuationMetadata(
                        reason = "restart",
                        previousEpoch = 2,
                        fromSetupLabel = "Default runner",
                        toSetupLabel = "GPU runner",
                    ),
            )

        assertThat(spawned.id).isEqualTo("abc12345")
        assertThat(spawned.stableSessionId).isEqualTo(sessionId.value.toString())
        assertThat(spawned.epoch).isEqualTo(3)
        assertThat(spawned.continuation?.reason).isEqualTo("restart")
        assertThat(spawned.continuation?.fromSetupLabel).isEqualTo("Default runner")
        assertThat(spawned.continuation?.toSetupLabel).isEqualTo("GPU runner")
        server.verify()
    }

    @Test
    fun `startHeadlessJob posts continuation setup labels`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentGatewayClient(builder.build(), props())
        val sessionId = WorkspaceAgentSessionId.random()

        server
            .expect(requestTo("http://runner:8090/agents/headless"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.kind").value("CLAUDE"))
            .andExpect(jsonPath("$.prompt").value("continue"))
            .andExpect(jsonPath("$.stableSessionId").value(sessionId.value.toString()))
            .andExpect(jsonPath("$.epoch").value(4))
            .andExpect(jsonPath("$.continuation.reason").value("restart"))
            .andExpect(jsonPath("$.continuation.previousEpoch").value(3))
            .andExpect(jsonPath("$.continuation.fromSetupLabel").value("Default runner"))
            .andExpect(jsonPath("$.continuation.toSetupLabel").value("GPU runner"))
            .andRespond(
                withSuccess(
                    """{"id":"job-1","status":"RUNNING","exitCode":null,"output":null}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val job =
            client.startHeadlessJob(
                workspace = workspace(),
                kind = WorkspaceAgentKind.CLAUDE,
                prompt = "continue",
                stableSessionId = sessionId,
                epoch = 4,
                continuation =
                    AgentGatewayClient.ContinuationMetadata(
                        reason = "restart",
                        previousEpoch = 3,
                        fromSetupLabel = "Default runner",
                        toSetupLabel = "GPU runner",
                    ),
            )

        assertThat(job.id).isEqualTo("job-1")
        server.verify()
    }

    @Test
    fun `cleanupStableSession deletes by stable session id`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentGatewayClient(builder.build(), props())
        val sessionId = WorkspaceAgentSessionId.random()

        server
            .expect(requestTo("http://runner:8090/agents/transcripts/${sessionId.value}"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withSuccess())

        client.cleanupStableSession(workspace(), sessionId)

        server.verify()
    }

    private fun workspace() =
        Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = "agent-runner-abcdef01",
            pvcName = "workspace-abcdef01",
            gatewayEndpoint = "http://runner:8090",
            status = WorkspaceStatus.READY,
            createdAt = Instant.parse("2026-06-12T09:00:00Z"),
            updatedAt = Instant.parse("2026-06-12T09:00:00Z"),
        )
}

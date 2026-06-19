package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
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
        val sessionId = WorkspaceAgentSessionId.parse("11111111-1111-4111-8111-111111111111")
        val ws = workspace()

        server
            .expect(requestTo("http://runner:8090/agents"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.kind").value("CLAUDE"))
            .andExpect(jsonPath("$.stableSessionId").value(sessionId.value.toString()))
            .andExpect(jsonPath("$.epoch").value(3))
            .andExpect(jsonPath("$.resumeCliSessionId").value("native-old"))
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
                resumeCliSessionId = "native-old",
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
        val sessionId = WorkspaceAgentSessionId.parse("22222222-2222-4222-8222-222222222222")
        val ws = workspace()
        val prompt = "continue with /workspace/private/brief.md"
        val outputFile = "/workspace/private/headless-result.txt"

        server
            .expect(requestTo("http://runner:8090/agents/headless"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.kind").value("CLAUDE"))
            .andExpect(jsonPath("$.prompt").value(prompt))
            .andExpect(jsonPath("$.stableSessionId").value(sessionId.value.toString()))
            .andExpect(jsonPath("$.epoch").value(4))
            .andExpect(jsonPath("$.continuation.reason").value("restart"))
            .andExpect(jsonPath("$.continuation.previousEpoch").value(3))
            .andExpect(jsonPath("$.continuation.fromSetupLabel").value("Default runner"))
            .andExpect(jsonPath("$.continuation.toSetupLabel").value("GPU runner"))
            .andRespond(
                withSuccess(
                    """{"id":"job-raw-1","status":"RUNNING","exitCode":null,"output":"$outputFile"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val job =
            client.startHeadlessJob(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                prompt = prompt,
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

        assertThat(job.id).isEqualTo("job-raw-1")
        assertThat(job.output).isEqualTo(outputFile)
        server.verify()
    }

    @Test
    fun `cleanupStableSession deletes by stable session id`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentGatewayClient(builder.build(), props())
        val sessionId = WorkspaceAgentSessionId.parse("44444444-4444-4444-8444-444444444444")

        server
            .expect(requestTo("http://runner:8090/agents/transcripts/${sessionId.value}"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withSuccess())

        client.cleanupStableSession(workspace(), sessionId)

        server.verify()
    }

    @Test
    fun `pollHeadlessJob records completed status with bounded labels`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val telemetry = RecordingTelemetry()
        val client = HttpAgentGatewayClient(builder.build(), props(), telemetry)
        val jobId = "job-raw-123"
        val outputFile = "/workspace/private/result.txt"

        server
            .expect(requestTo("http://runner:8090/agents/headless/$jobId"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"id":"$jobId","status":"COMPLETED","exitCode":0,"output":"$outputFile"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val job = client.pollHeadlessJob(workspace(), jobId)

        assertThat(job.id).isEqualTo(jobId)
        assertThat(job.output).isEqualTo(outputFile)
        telemetry.operations.single().let { event ->
            assertThat(event.operation).isEqualTo(OperationLabel.OTHER)
            assertThat(event.mode).isEqualTo(ModeLabel.HEADLESS)
            assertThat(event.outcome).isEqualTo(OutcomeLabel.SUCCESS)
            assertThat(event.reason).isEqualTo(FailureReasonLabel.NONE)
            assertThat(event.labels()).doesNotContain(jobId, outputFile, "COMPLETED")
        }
        server.verify()
    }

    @Test
    fun `pollHeadlessJob records failed and unknown statuses with bounded labels`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val telemetry = RecordingTelemetry()
        val client = HttpAgentGatewayClient(builder.build(), props(), telemetry)

        server
            .expect(requestTo("http://runner:8090/agents/headless/job-failed-secret"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"id":"job-failed-secret","status":"FAILED","exitCode":1,"output":"stacktrace /tmp/raw.log"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server
            .expect(requestTo("http://runner:8090/agents/headless/job-unknown-secret"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """
                    {
                      "id": "job-unknown-secret",
                      "status": "RAW_GATEWAY_TIMEOUT_FOR_/workspace/private",
                      "exitCode": null
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val failed = client.pollHeadlessJob(workspace(), "job-failed-secret")
        val unknown = client.pollHeadlessJob(workspace(), "job-unknown-secret")

        assertThat(failed.status).isEqualTo(AgentGatewayClient.HeadlessStatus.FAILED)
        assertThat(unknown.status).isEqualTo(AgentGatewayClient.HeadlessStatus.FAILED)
        assertThat(telemetry.operations).hasSize(2)
        assertThat(telemetry.operations.map { it.reason }).containsExactly(
            FailureReasonLabel.PROCESS_EXITED,
            FailureReasonLabel.TIMEOUT,
        )
        assertThat(telemetry.operations.flatMap { it.labels() }).doesNotContain(
            "job-failed-secret",
            "job-unknown-secret",
            "stacktrace /tmp/raw.log",
            "RAW_GATEWAY_TIMEOUT_FOR_/workspace/private",
        )
        server.verify()
    }

    private fun workspace() =
        Workspace(
            id = WorkspaceId.parse("55555555-5555-4555-8555-555555555555"),
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

    private class RecordingTelemetry : AgentsApiTelemetry {
        val operations = mutableListOf<OperationTelemetry>()

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }
    }

    private fun OperationTelemetry.labels(): Set<String> =
        setOf(
            operation.label,
            mode.label,
            outcome.label,
            reason.label,
        )
}

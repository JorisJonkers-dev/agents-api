package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.ChatGenerationProperties
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant

class RunnerPodChatGeneratorTest {
    // region: parseStreamJsonLine unit tests

    @Test
    fun `parseStreamJsonLine extracts whole message from assistant event`() {
        val line =
            """{"type":"assistant","message":{"content":[{"type":"text","text":"hello world"}]}}"""
        val event = parseStreamJsonLine(line)

        assertThat(event.message).isEqualTo("hello world")
        assertThat(event.delta).isNull()
        assertThat(event.result).isNull()
    }

    @Test
    fun `parseStreamJsonLine extracts token delta from content_block_delta event`() {
        val line =
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,""" +
                """"delta":{"type":"text_delta","text":"al"}}}"""
        val event = parseStreamJsonLine(line)

        assertThat(event.delta).isEqualTo("al")
        assertThat(event.message).isNull()
        assertThat(event.result).isNull()
    }

    @Test
    fun `parseStreamJsonLine ignores non-text stream_event deltas`() {
        val line = """{"type":"stream_event","event":{"type":"message_start","message":{}}}"""
        val event = parseStreamJsonLine(line)

        assertThat(event.delta).isNull()
        assertThat(event.message).isNull()
        assertThat(event.result).isNull()
    }

    @Test
    fun `parseStreamJsonLine extracts result from result success event`() {
        val line = """{"type":"result","subtype":"success","result":"hello world"}"""
        val event = parseStreamJsonLine(line)

        assertThat(event.result).isEqualTo("hello world")
        assertThat(event.message).isNull()
        assertThat(event.delta).isNull()
    }

    @Test
    fun `parseStreamJsonLine returns empty event for system init event`() {
        val event = parseStreamJsonLine("""{"type":"system","subtype":"init","cwd":"/workspace"}""")
        assertThat(event).isEqualTo(StreamJsonEvent())
    }

    @Test
    fun `parseStreamJsonLine returns empty event for rate_limit_event`() {
        val event = parseStreamJsonLine("""{"type":"rate_limit_event","limit":100}""")
        assertThat(event).isEqualTo(StreamJsonEvent())
    }

    @Test
    fun `parseStreamJsonLine returns empty event for blank line`() {
        assertThat(parseStreamJsonLine("")).isEqualTo(StreamJsonEvent())
    }

    @Test
    fun `parseStreamJsonLine returns empty event for malformed JSON`() {
        assertThat(parseStreamJsonLine("{not valid json}")).isEqualTo(StreamJsonEvent())
    }

    @Test
    fun `parseStreamJsonLine handles assistant message with multiple text content elements`() {
        val line =
            """{"type":"assistant","message":{"content":[{"type":"text","text":"hello"},{"type":"text","text":" world"}]}}"""
        val event = parseStreamJsonLine(line)

        assertThat(event.message).isEqualTo("hello world")
    }

    // endregion

    // region: generator returns empty when workspace is not resolvable

    @Test
    fun `generate returns empty string when runnerPodWorkspaceId is null`() {
        val props = ChatGenerationProperties(backend = "runner-pod", runnerPodWorkspaceId = null)
        val gateway = mockk<AgentGatewayClient>()
        val restClient = mockk<RestClient>()
        val workspaceRepo = mockk<WorkspaceRepository>()
        val generator = RunnerPodChatGenerator(gateway, restClient, workspaceRepo, props)

        val chunks = mutableListOf<String>()
        val result = generator.generate("some prompt") { chunks.add(it) }

        assertThat(result).isEmpty()
        assertThat(chunks).isEmpty()
    }

    @Test
    fun `generate returns empty string when workspace is not found`() {
        val wsId = "11111111-1111-4111-8111-111111111111"
        val props = ChatGenerationProperties(backend = "runner-pod", runnerPodWorkspaceId = wsId)
        val gateway = mockk<AgentGatewayClient>()
        val restClient = mockk<RestClient>()
        val workspaceRepo = mockk<WorkspaceRepository>()
        every { workspaceRepo.findById(WorkspaceId.parse(wsId)) } returns null

        val generator = RunnerPodChatGenerator(gateway, restClient, workspaceRepo, props)
        val chunks = mutableListOf<String>()
        val result = generator.generate("some prompt") { chunks.add(it) }

        assertThat(result).isEmpty()
        assertThat(chunks).isEmpty()
    }

    // endregion

    // region: gateway routing and typed kind

    @Test
    fun `startHeadlessJob routes through AgentGatewayClient with typed kind`() {
        val wsId = "22222222-2222-4222-8222-222222222222"
        val props =
            ChatGenerationProperties(
                backend = "runner-pod",
                runnerPodWorkspaceId = wsId,
                runnerPodAgentKind = WorkspaceAgentKind.CODEX,
                runnerPodEnableKbHooks = false,
            )
        val workspace = stubWorkspace(wsId, "http://gateway:8080")
        val workspaceRepo = mockk<WorkspaceRepository>()
        every { workspaceRepo.findById(WorkspaceId.parse(wsId)) } returns workspace

        val capturedRequest = slot<AgentGatewayClient.HeadlessJobRequest>()
        val gateway = mockk<AgentGatewayClient>()
        every { gateway.startHeadlessJob(capture(capturedRequest)) } throws RuntimeException("stream not needed")

        val restClient = mockk<RestClient>()
        val generator = RunnerPodChatGenerator(gateway, restClient, workspaceRepo, props)

        generator.generate("my prompt") {}

        // gateway was called
        verify(exactly = 1) { gateway.startHeadlessJob(any()) }
        // typed kind is forwarded
        assertThat(capturedRequest.captured.kind).isEqualTo(WorkspaceAgentKind.CODEX)
        // prompt is forwarded
        assertThat(capturedRequest.captured.prompt).isEqualTo("my prompt")
        // kb hooks default off
        assertThat(capturedRequest.captured.enableKbHooks).isFalse()
        // partial-messages always on for SSE streaming
        assertThat(capturedRequest.captured.partialMessages).isTrue()
        // workspace object is the resolved one
        assertThat(capturedRequest.captured.workspace).isSameAs(workspace)
    }

    @Test
    fun `startHeadlessJob forwards enableKbHooks=true when configured`() {
        val wsId = "33333333-3333-4333-8333-333333333333"
        val props =
            ChatGenerationProperties(
                backend = "runner-pod",
                runnerPodWorkspaceId = wsId,
                runnerPodAgentKind = WorkspaceAgentKind.CLAUDE,
                runnerPodEnableKbHooks = true,
            )
        val workspace = stubWorkspace(wsId, "http://gateway:8080")
        val workspaceRepo = mockk<WorkspaceRepository>()
        every { workspaceRepo.findById(WorkspaceId.parse(wsId)) } returns workspace

        val capturedRequest = slot<AgentGatewayClient.HeadlessJobRequest>()
        val gateway = mockk<AgentGatewayClient>()
        every { gateway.startHeadlessJob(capture(capturedRequest)) } throws RuntimeException("stream not needed")

        val restClient = mockk<RestClient>()
        val generator = RunnerPodChatGenerator(gateway, restClient, workspaceRepo, props)

        generator.generate("kb prompt") {}

        assertThat(capturedRequest.captured.enableKbHooks).isTrue()
    }

    @Test
    fun `generate returns empty when workspace has no gateway endpoint`() {
        val wsId = "44444444-4444-4444-8444-444444444444"
        val props = ChatGenerationProperties(backend = "runner-pod", runnerPodWorkspaceId = wsId)
        val workspace = stubWorkspace(wsId, gatewayEndpoint = null)
        val workspaceRepo = mockk<WorkspaceRepository>()
        every { workspaceRepo.findById(WorkspaceId.parse(wsId)) } returns workspace

        val gateway = mockk<AgentGatewayClient>()
        val restClient = mockk<RestClient>()
        val generator = RunnerPodChatGenerator(gateway, restClient, workspaceRepo, props)

        val result = generator.generate("prompt") {}

        assertThat(result).isEmpty()
        verify(exactly = 0) { gateway.startHeadlessJob(any()) }
    }

    // endregion

    @Test
    fun `generate streams SSE output on successful job launch`() {
        val wsId = "55555555-5555-4555-8555-555555555555"
        val props =
            ChatGenerationProperties(
                backend = "runner-pod",
                runnerPodWorkspaceId = wsId,
                runnerPodAgentKind = WorkspaceAgentKind.CLAUDE,
            )
        val workspace = stubWorkspace(wsId, "http://gateway:8080")
        val workspaceRepo = mockk<WorkspaceRepository>()
        every { workspaceRepo.findById(WorkspaceId.parse(wsId)) } returns workspace

        val job =
            AgentGatewayClient.HeadlessJob(
                id = "job-stream-1",
                status = AgentGatewayClient.HeadlessStatus.RUNNING,
                exitCode = null,
                output = null,
            )
        val gateway = mockk<AgentGatewayClient>()
        every { gateway.startHeadlessJob(any()) } returns job

        // Use a mock RestClient that returns a single SSE result line
        val sseBody =
            """
            event:line
            data:{"type":"result","subtype":"success","result":"streaming answer"}

            """.trimIndent()
        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()
        server
            .expect(requestTo("http://gateway:8080/agents/headless/job-stream-1/stream"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(sseBody, MediaType.TEXT_EVENT_STREAM))

        val generator = RunnerPodChatGenerator(gateway, restClientBuilder.build(), workspaceRepo, props)
        val chunks = mutableListOf<String>()
        val result = generator.generate("stream prompt") { chunks.add(it) }

        assertThat(result).isEqualTo("streaming answer")
        server.verify()
    }

    private fun stubWorkspace(
        id: String,
        gatewayEndpoint: String?,
    ) = Workspace(
        id = WorkspaceId.parse(id),
        name = "test-workspace",
        repoUrl = null,
        branch = null,
        podName = "pod-1",
        pvcName = "pvc-1",
        gatewayEndpoint = gatewayEndpoint,
        status = WorkspaceStatus.READY,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}

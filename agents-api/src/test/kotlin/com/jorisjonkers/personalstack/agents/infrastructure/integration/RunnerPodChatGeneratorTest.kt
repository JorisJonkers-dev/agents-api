package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.ChatGenerationProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

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
        val restClient = mockk<RestClient>()
        val workspaceRepo = mockk<WorkspaceRepository>()
        val generator = RunnerPodChatGenerator(restClient, workspaceRepo, props)

        val chunks = mutableListOf<String>()
        val result = generator.generate("some prompt") { chunks.add(it) }

        assertThat(result).isEmpty()
        assertThat(chunks).isEmpty()
    }

    @Test
    fun `generate returns empty string when workspace is not found`() {
        val wsId = "11111111-1111-4111-8111-111111111111"
        val props = ChatGenerationProperties(backend = "runner-pod", runnerPodWorkspaceId = wsId)
        val restClient = mockk<RestClient>()
        val workspaceRepo = mockk<WorkspaceRepository>()
        every { workspaceRepo.findById(WorkspaceId.parse(wsId)) } returns null

        val generator = RunnerPodChatGenerator(restClient, workspaceRepo, props)
        val chunks = mutableListOf<String>()
        val result = generator.generate("some prompt") { chunks.add(it) }

        assertThat(result).isEmpty()
        assertThat(chunks).isEmpty()
    }

    // endregion
}

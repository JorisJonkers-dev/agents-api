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
    fun `parseStreamJsonLine extracts text from assistant message event`() {
        val line =
            """{"type":"assistant","message":{"content":[{"type":"text","text":"hello world"}]}}"""
        val (text, result) = parseStreamJsonLine(line)

        assertThat(text).isEqualTo("hello world")
        assertThat(result).isNull()
    }

    @Test
    fun `parseStreamJsonLine extracts result from result success event`() {
        val line = """{"type":"result","subtype":"success","result":"hello world"}"""
        val (text, result) = parseStreamJsonLine(line)

        assertThat(text).isNull()
        assertThat(result).isEqualTo("hello world")
    }

    @Test
    fun `parseStreamJsonLine returns null pair for system init event`() {
        val line = """{"type":"system","subtype":"init","cwd":"/workspace"}"""
        val (text, result) = parseStreamJsonLine(line)

        assertThat(text).isNull()
        assertThat(result).isNull()
    }

    @Test
    fun `parseStreamJsonLine returns null pair for rate_limit_event`() {
        val line = """{"type":"rate_limit_event","limit":100}"""
        val (text, result) = parseStreamJsonLine(line)

        assertThat(text).isNull()
        assertThat(result).isNull()
    }

    @Test
    fun `parseStreamJsonLine returns null pair for blank line`() {
        val (text, result) = parseStreamJsonLine("")
        assertThat(text).isNull()
        assertThat(result).isNull()
    }

    @Test
    fun `parseStreamJsonLine returns null pair for malformed JSON`() {
        val (text, result) = parseStreamJsonLine("{not valid json}")
        assertThat(text).isNull()
        assertThat(result).isNull()
    }

    @Test
    fun `parseStreamJsonLine emits text once and result is authoritative when both present in stream`() {
        val assistantLine =
            """{"type":"assistant","message":{"content":[{"type":"text","text":"hello world"}]}}"""
        val resultLine = """{"type":"result","subtype":"success","result":"hello world"}"""

        val chunks = mutableListOf<String>()
        val accumulated = StringBuilder()

        val (assistantText, _) = parseStreamJsonLine(assistantLine)
        assistantText?.let {
            accumulated.append(it)
            chunks.add(it)
        }
        val (_, resultText) = parseStreamJsonLine(resultLine)
        val finalAnswer = resultText ?: accumulated.toString()

        // onChunk was called once with the assistant text
        assertThat(chunks).containsExactly("hello world")
        // final return value is the result field
        assertThat(finalAnswer).isEqualTo("hello world")
    }

    @Test
    fun `parseStreamJsonLine handles assistant message with multiple text content elements`() {
        val line =
            """{"type":"assistant","message":{"content":[{"type":"text","text":"hello"},{"type":"text","text":" world"}]}}"""
        val (text, result) = parseStreamJsonLine(line)

        assertThat(text).isEqualTo("hello world")
        assertThat(result).isNull()
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

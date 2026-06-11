package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.RagProperties
import com.jorisjonkers.personalstack.agents.domain.port.KnowledgeWritePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class KnowledgeMcpClientTest {
    private fun props(enabled: Boolean = true) =
        RagProperties(
            enabled = enabled,
            knowledgeMcpUrl = "http://kb",
            knowledgeMcpToken = "token-123",
            lightragUrl = "http://lightrag",
            recallMode = "hybrid",
        )

    @Test
    fun `retrieve calls canonical dot-form recall tool and parses structured hits`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = KnowledgeMcpClient(builder.build(), props())

        server
            .expect(requestTo("http://kb/mcp"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-123"))
            .andExpect(jsonPath("$.method").value("tools/call"))
            .andExpect(jsonPath("$.params.name").value("knowledge.recall"))
            .andExpect(jsonPath("$.params.arguments.query").value("agent hooks"))
            .andExpect(jsonPath("$.params.arguments.limit").value(2))
            .andExpect(jsonPath("$.params.arguments.mode").value("hybrid"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "result": {
                        "structuredContent": {
                          "hits": [
                            {
                              "id": "01KTEST0000000000000000000",
                              "scope": "project:agents",
                              "title": "Agent hooks use canonical MCP names",
                              "snippet": "Use knowledge.recall rather than legacy underscore aliases.",
                              "score": 0.91
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val snippets = client.retrieve("agent hooks", limit = 2)

        assertThat(snippets).singleElement().satisfies({ snippet ->
            assertThat(snippet.id).isEqualTo("01KTEST0000000000000000000")
            assertThat(snippet.source).isEqualTo("kb:project:agents:Agent hooks use canonical MCP names")
            assertThat(snippet.text).isEqualTo("Use knowledge.recall rather than legacy underscore aliases.")
            assertThat(snippet.score).isEqualTo(0.91)
        })
        server.verify()
    }

    @Test
    fun `ingestNote calls canonical dot-form capture tool`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = KnowledgeMcpClient(builder.build(), props())

        server
            .expect(requestTo("http://kb/mcp"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-123"))
            .andExpect(jsonPath("$.method").value("tools/call"))
            .andExpect(jsonPath("$.params.name").value("knowledge.capture_lesson"))
            .andExpect(jsonPath("$.params.arguments.title").value("Canonical MCP names"))
            .andExpect(jsonPath("$.params.arguments.body").value("Agents API uses dot-form tool names."))
            .andExpect(jsonPath("$.params.arguments.scope").value("project:agents"))
            .andExpect(jsonPath("$.params.arguments.source").value("agents-ui:auto-capture:session-1"))
            .andExpect(jsonPath("$.params.arguments.session_id").value("session-1"))
            .andExpect(jsonPath("$.params.arguments.confidence").value(0.74))
            .andExpect(jsonPath("$.params.arguments.tags[0]").value("mcp"))
            .andRespond(
                withSuccess(
                    """{"jsonrpc":"2.0","id":1,"result":{"structuredContent":{"id":"01KCAPTURE"}}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        client.ingestNote(
            KnowledgeWritePort.CaptureRequest(
                title = "Canonical MCP names",
                body = "Agents API uses dot-form tool names.",
                scope = "project:agents",
                tags = listOf("mcp"),
                source = "agents-ui:auto-capture:session-1",
                sessionId = "session-1",
                confidence = 0.74,
            ),
        )

        server.verify()
    }

    @Test
    fun `findDuplicateEvidence returns the top recall hit when it clears the threshold`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = KnowledgeMcpClient(builder.build(), props())

        server
            .expect(requestTo("http://kb/mcp"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.params.name").value("knowledge.recall"))
            .andExpect(jsonPath("$.params.arguments.query").value("duplicate query"))
            .andExpect(jsonPath("$.params.arguments.limit").value(1))
            .andRespond(
                withSuccess(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "result": {
                        "structuredContent": {
                          "hits": [
                            {
                              "id": "01KDUPLICATE",
                              "scope": "project:agents",
                              "title": "Existing capture",
                              "snippet": "Existing body.",
                              "score": 0.9
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val duplicate = client.findDuplicateEvidence("duplicate query", minScore = 0.86)

        assertThat(duplicate).isNotNull
        assertThat(duplicate!!.id).isEqualTo("01KDUPLICATE")
        assertThat(duplicate.source).isEqualTo("kb:project:agents:Existing capture")
        assertThat(duplicate.score).isEqualTo(0.9)
        server.verify()
    }

    @Test
    fun `disabled client does not call the MCP endpoint`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = KnowledgeMcpClient(builder.build(), props(enabled = false))

        assertThat(client.retrieve("ignored", limit = 1)).isEmpty()
        client.ingestNote("ignored", "ignored", "project:agents")

        server.verify()
    }
}

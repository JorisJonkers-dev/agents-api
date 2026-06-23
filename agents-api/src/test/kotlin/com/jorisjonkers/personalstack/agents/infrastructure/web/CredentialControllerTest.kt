package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.infrastructure.integration.HttpCredentialWorkerClient
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.HttpClientErrorException

class CredentialControllerTest {
    private val worker = mockk<HttpCredentialWorkerClient>()
    private val objectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(CredentialController(worker))
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    private fun workerStatus(
        id: String = "sess-1",
        provider: String = "claude",
        phase: String = "starting",
        authorizeUrl: String? = null,
        deviceCode: String? = null,
        verificationUrl: String? = null,
        needsRedirectUrl: Boolean = false,
    ) = HttpCredentialWorkerClient.SessionStatus(
        id = id,
        provider = provider,
        phase = phase,
        authorizeUrl = authorizeUrl,
        deviceCode = deviceCode,
        verificationUrl = verificationUrl,
        needsRedirectUrl = needsRedirectUrl,
        message = null,
        error = null,
        updatedAt = "2026-06-20T10:00:00Z",
    )

    @Test
    fun `POST sessions starts a claude session and returns 201`() {
        every { worker.start(provider = "claude", updatedBy = any()) } returns
            workerStatus(provider = "claude", phase = "awaiting_url", authorizeUrl = "https://claude.ai/authorize?x=1")

        mockMvc
            .perform(
                post("/api/v1/credentials/sessions")
                    .header("X-User-Id", "operator@example.com")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("provider" to "claude"))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("sess-1"))
            .andExpect(jsonPath("$.provider").value("claude"))
            .andExpect(jsonPath("$.phase").value("awaiting_url"))
            .andExpect(jsonPath("$.authorizeUrl").value("https://claude.ai/authorize?x=1"))
    }

    @Test
    fun `POST sessions resolves updatedBy from the principal not the request body`() {
        val updatedBy = slot<String>()
        every { worker.start(provider = "codex", updatedBy = capture(updatedBy)) } returns
            workerStatus(provider = "codex")

        mockMvc
            .perform(
                post("/api/v1/credentials/sessions")
                    .header("X-User-Id", "real-operator")
                    .contentType(MediaType.APPLICATION_JSON)
                    // A malicious client tries to spoof updatedBy via the body.
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("provider" to "codex", "updatedBy" to "attacker"),
                        ),
                    ),
            ).andExpect(status().isCreated)

        // The worker must be told the forward-auth identity, never the body value.
        verify { worker.start(provider = "codex", updatedBy = "real-operator") }
        assert(updatedBy.captured == "real-operator")
    }

    @Test
    fun `POST sessions with an invalid provider returns 422 without calling the worker`() {
        mockMvc
            .perform(
                post("/api/v1/credentials/sessions")
                    .header("X-User-Id", "operator")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("provider" to "gemini"))),
            ).andExpect(status().isUnprocessableContent)

        verify(exactly = 0) { worker.start(any(), any()) }
    }

    @Test
    fun `POST sessions relays a worker 409 as problem+json carrying the worker message`() {
        every { worker.start(any(), any()) } throws
            HttpClientErrorException.create(
                HttpStatus.CONFLICT,
                "Conflict",
                org.springframework.http.HttpHeaders.EMPTY,
                """{"error":"a login session is already in progress"}""".toByteArray(),
                null,
            )

        mockMvc
            .perform(
                post("/api/v1/credentials/sessions")
                    .header("X-User-Id", "operator")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("provider" to "claude"))),
            ).andExpect(status().isConflict)
            // The browser client reads detail/title/status; a bare {"error":…}
            // body rendered "HTTP undefined" and hid the real reason.
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.title").value("Credential worker request failed"))
            .andExpect(jsonPath("$.detail").value("a login session is already in progress"))
    }

    @Test
    fun `POST sessions maps a blank worker error body to a generic detail`() {
        every { worker.start(any(), any()) } throws
            HttpClientErrorException.create(
                HttpStatus.BAD_GATEWAY,
                "Bad Gateway",
                org.springframework.http.HttpHeaders.EMPTY,
                ByteArray(0),
                null,
            )

        mockMvc
            .perform(
                post("/api/v1/credentials/sessions")
                    .header("X-User-Id", "operator")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("provider" to "claude"))),
            ).andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.detail").value("credential worker request failed"))
    }

    @Test
    fun `GET session returns the worker status`() {
        every { worker.status("sess-9") } returns
            workerStatus(
                id = "sess-9",
                provider = "codex",
                phase = "awaiting_device",
                deviceCode = "ABCD-1234",
                verificationUrl = "https://auth.openai.com/device",
            )

        mockMvc
            .perform(get("/api/v1/credentials/sessions/sess-9").header("X-User-Id", "operator"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.phase").value("awaiting_device"))
            .andExpect(jsonPath("$.deviceCode").value("ABCD-1234"))
            .andExpect(jsonPath("$.verificationUrl").value("https://auth.openai.com/device"))
    }

    @Test
    fun `GET unknown session relays the worker 404`() {
        every { worker.status("nope") } throws
            HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                org.springframework.http.HttpHeaders.EMPTY,
                """{"error":"no matching session"}""".toByteArray(),
                null,
            )

        mockMvc
            .perform(get("/api/v1/credentials/sessions/nope").header("X-User-Id", "operator"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail").value("no matching session"))
    }

    @Test
    fun `POST redirect relays the url and returns ok`() {
        every { worker.submitRedirect("sess-1", "https://claude.ai/redirect?code=x") } returns
            HttpCredentialWorkerClient.OkResult(ok = true)

        mockMvc
            .perform(
                post("/api/v1/credentials/sessions/sess-1/redirect")
                    .header("X-User-Id", "operator")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("url" to "https://claude.ai/redirect?code=x"))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.ok").value(true))
    }

    @Test
    fun `POST redirect with a blank url returns 422`() {
        mockMvc
            .perform(
                post("/api/v1/credentials/sessions/sess-1/redirect")
                    .header("X-User-Id", "operator")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("url" to ""))),
            ).andExpect(status().isUnprocessableContent)

        verify(exactly = 0) { worker.submitRedirect(any(), any()) }
    }

    @Test
    fun `POST cancel returns the worker ack`() {
        every { worker.cancel("sess-1") } returns HttpCredentialWorkerClient.OkResult(ok = true)

        mockMvc
            .perform(post("/api/v1/credentials/sessions/sess-1/cancel").header("X-User-Id", "operator"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ok").value(true))
    }
}

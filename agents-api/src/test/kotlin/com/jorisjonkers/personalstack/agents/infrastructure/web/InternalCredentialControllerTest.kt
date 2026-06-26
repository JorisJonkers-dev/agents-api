package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.domain.model.AgentCredentialProvider
import com.jorisjonkers.personalstack.agents.domain.model.AgentOauthCredential
import com.jorisjonkers.personalstack.agents.domain.port.AgentCredentialRepository
import com.jorisjonkers.personalstack.agents.infrastructure.credentials.CredentialValidationResult
import com.jorisjonkers.personalstack.agents.infrastructure.credentials.CredentialValidator
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class InternalCredentialControllerTest {
    private val store = mockk<AgentCredentialRepository>()
    private val validator = mockk<CredentialValidator>()
    private val objectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(InternalCredentialController(store, validator))
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    fun `POST credentials derives updatedBy from the owner userId, never the request body`() {
        val captured = slot<AgentOauthCredential>()
        every { store.upsert(capture(captured)) } answers { captured.captured }
        every { validator.validate(any(), any()) } returns CredentialValidationResult.UNKNOWN

        val result =
            mockMvc
                .perform(
                    post("/api/v1/internal/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                mapOf(
                                    "userId" to "u-1",
                                    "provider" to "CLAUDE",
                                    "payload" to mapOf("oauth_token" to "sk-ant-secret"),
                                    // A caller cannot forge authorship: any updatedBy in the
                                    // body is ignored and the stored value tracks the owner.
                                    "updatedBy" to "attacker",
                                ),
                            ),
                        ),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.provider").value("CLAUDE"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andReturn()

        assertThat(captured.captured.userId).isEqualTo("u-1")
        assertThat(captured.captured.updatedBy).isEqualTo("u-1")
        assertThat(captured.captured.provider).isEqualTo(AgentCredentialProvider.CLAUDE)
        assertThat(captured.captured.valid).isNull()
        assertThat(captured.captured.validatedAt).isNull()
        assertThat(result.response.contentAsString).doesNotContain("sk-ant-secret")
        verify(exactly = 0) { store.markValidity(any(), any(), any()) }
    }

    @Test
    fun `POST credentials accepts Claude credentials json without legacy oauth token`() {
        every { store.upsert(any()) } answers { arg<AgentOauthCredential>(0) }
        every { validator.validate(AgentCredentialProvider.CLAUDE, any()) } returns CredentialValidationResult.UNKNOWN

        mockMvc
            .perform(
                post("/api/v1/internal/credentials")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "userId" to "u-credentials-json",
                                "provider" to "CLAUDE",
                                "payload" to
                                    mapOf(
                                        "credentials_json" to """{"claudeAiOauth":{"accessToken":"secret"}}""",
                                    ),
                                "updatedBy" to "worker",
                            ),
                        ),
                    ),
            ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.provider").value("CLAUDE"))
            .andExpect(jsonPath("$.status").value("UNKNOWN"))
            .andExpect(content().string(not(containsString("accessToken"))))

        verify(exactly = 0) { store.markValidity(any(), any(), any()) }
    }

    @Test
    fun `POST credentials ingests codex payload and marks verified success only when validator succeeds`() {
        every { store.upsert(any()) } answers { arg<AgentOauthCredential>(0) }
        every { validator.validate(AgentCredentialProvider.CODEX, any()) } returns CredentialValidationResult.VALID
        every { store.markValidity("u-2", AgentCredentialProvider.CODEX, true) } just runs

        mockMvc
            .perform(
                post("/api/v1/internal/credentials")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "userId" to "u-2",
                                "provider" to "CODEX",
                                "payload" to
                                    mapOf(
                                        "auth_json" to """{"access_token":"secret"}""",
                                        "config_toml" to "profile = \"default\"",
                                    ),
                                "updatedBy" to "worker",
                            ),
                        ),
                    ),
            ).andExpect(status().isAccepted)
            .andExpect(content().string(not(containsString("access_token"))))

        verify { store.markValidity("u-2", AgentCredentialProvider.CODEX, true) }
    }

    @Test
    fun `POST credentials marks explicit invalid only for validator explicit invalid`() {
        every { store.upsert(any()) } answers { arg<AgentOauthCredential>(0) }
        every { validator.validate(any(), any()) } returns CredentialValidationResult.EXPLICIT_INVALID
        every { store.markValidity("u-3", AgentCredentialProvider.CLAUDE, false) } just runs

        mockMvc
            .perform(
                post("/api/v1/internal/credentials")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "userId" to "u-3",
                                "provider" to "CLAUDE",
                                "payload" to mapOf("oauth_token" to "bad"),
                                "updatedBy" to "worker",
                            ),
                        ),
                    ),
            ).andExpect(status().isAccepted)

        verify { store.markValidity("u-3", AgentCredentialProvider.CLAUDE, false) }
    }

    @Test
    fun `POST credentials ignores validator failures and keeps request path successful`() {
        every { store.upsert(any()) } answers { arg<AgentOauthCredential>(0) }
        every { validator.validate(any(), any()) } throws IllegalStateException("probe failed with secret")

        mockMvc
            .perform(
                post("/api/v1/internal/credentials")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "userId" to "u-4",
                                "provider" to "CLAUDE",
                                "payload" to mapOf("oauth_token" to "secret"),
                                "updatedBy" to "worker",
                            ),
                        ),
                    ),
            ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("UNKNOWN"))

        verify(exactly = 0) { store.markValidity(any(), any(), any()) }
    }

    @Test
    fun `POST credentials rejects missing provider-specific payload fields`() {
        mockMvc
            .perform(
                post("/api/v1/internal/credentials")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "userId" to "u-5",
                                "provider" to "CODEX",
                                "payload" to mapOf("auth_json" to "{}"),
                                "updatedBy" to "worker",
                            ),
                        ),
                    ),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { store.upsert(any()) }
        verify(exactly = 0) { validator.validate(any(), any()) }
    }

    @Test
    fun `POST credentials requires uppercase provider enum values`() {
        mockMvc
            .perform(
                post("/api/v1/internal/credentials")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "userId" to "u-6",
                                "provider" to "claude",
                                "payload" to mapOf("oauth_token" to "secret"),
                                "updatedBy" to "worker",
                            ),
                        ),
                    ),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { store.upsert(any()) }
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentLoginStore
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AgentLoginControllerTest {
    private val logins = mockk<AgentLoginStore>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(AgentLoginController(logins))
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    fun `reports presence per provider`() {
        every { logins.statuses() } returns
            listOf(
                AgentLoginStore.AgentLoginStatus(WorkspaceAgentKind.CLAUDE, present = true),
                AgentLoginStore.AgentLoginStatus(WorkspaceAgentKind.CODEX, present = false),
            )

        mockMvc
            .perform(get("/api/v1/agent-logins"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.logins[0].kind").value("claude"))
            .andExpect(jsonPath("$.logins[0].present").value(true))
            .andExpect(jsonPath("$.logins[1].kind").value("codex"))
            .andExpect(jsonPath("$.logins[1].present").value(false))
    }

    // A response carrying anything derived from the login itself would put an
    // OAuth token on the wire; presence is the entire contract.
    @Test
    fun `reports nothing but the provider and whether a login is present`() {
        every { logins.statuses() } returns
            listOf(AgentLoginStore.AgentLoginStatus(WorkspaceAgentKind.CLAUDE, present = true))

        mockMvc
            .perform(get("/api/v1/agent-logins"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.logins[0].length()").value(2))
    }

    @Test
    fun `reports an empty answer rather than failing when no provider has a login`() {
        every { logins.statuses() } returns
            listOf(
                AgentLoginStore.AgentLoginStatus(WorkspaceAgentKind.CLAUDE, present = false),
                AgentLoginStore.AgentLoginStatus(WorkspaceAgentKind.CODEX, present = false),
            )

        mockMvc
            .perform(get("/api/v1/agent-logins"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.logins[0].present").value(false))
            .andExpect(jsonPath("$.logins[1].present").value(false))
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssue
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssueCode
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class AgentSetupExceptionHandlerTest {
    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(TestController())
            .setControllerAdvice(AgentSetupExceptionHandler())
            .build()

    @Test
    fun `validation problem detail is stable and redacted`() {
        mockMvc
            .perform(get("/boom"))
            .andExpect(status().`is`(422))
            .andExpect(jsonPath("$.type").value("https://jorisjonkers.dev/errors/agent-setup-validation"))
            .andExpect(jsonPath("$.title").value("Agent setup validation failed"))
            .andExpect(jsonPath("$.detail").value("Agent setup validation failed."))
            .andExpect(jsonPath("$.errors[0].field").value("agentSetup.SECRET_MISSING"))
            .andExpect(jsonPath("$.errors[0].message").value("SECRET_MISSING"))
            .andExpect(content().string(not(containsString("kb-secret"))))
            .andExpect(content().string(not(containsString("secret-value"))))
    }

    @RestController
    private class TestController {
        @GetMapping("/boom")
        fun boom(): Unit =
            throw AgentSetupValidationException(
                AgentSetupValidationResult(
                    target = AgentSetupRef(AgentSetupId("target"), AgentSetupVersion(2)),
                    valid = false,
                    issues =
                        listOf(
                            AgentSetupValidationIssue(
                                code = AgentSetupValidationIssueCode.SECRET_MISSING,
                                message = "Secret agents/kb-secret with secret-value is missing",
                            ),
                        ),
                ),
            )
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Files
import java.nio.file.Path

class HealthControllerTest {
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(HealthController())
            .build()

    @Test
    fun `health returns ok with service name`() {
        mockMvc
            .get("/api/v1/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ok") }
                jsonPath("$.service") { value("agents-api") }
            }
    }

    @Test
    fun `application keeps agents api service identity and actuator exposure`() {
        val application = readProjectFile("src/main/resources/application.yml")

        assertThat(application).contains("name: agents-api")
        assertThat(application).contains("base-path: /api/actuator")
        assertThat(application).contains("include: health,info,prometheus,metrics")
        assertThat(application).contains("service.name: \${spring.application.name}")
        assertThat(application).contains("deployment.environment: \${DEPLOYMENT_ENVIRONMENT:unknown}")
        assertThat(application).contains("probes:")
        assertThat(application).contains("enabled: true")
        assertThat(application).contains("liveness:")
        assertThat(application).contains("include: livenessState,ping")
        assertThat(application).contains("readiness:")
        assertThat(application).contains("include: readinessState")
    }

    @Test
    fun `json logs keep agents api custom identity fields`() {
        val logback = readProjectFile("src/main/resources/logback-spring.xml")

        assertThat(logback).contains(
            "\"service\":\"agents-api\"",
            "\"service.name\":\"agents-api\"",
            "\"service.version\":\"\${serviceVersion}\"",
            "\"deployment.environment\":\"\${deploymentEnvironment}\"",
        )
    }

    private fun readProjectFile(path: String): String {
        val file =
            listOf(
                Path.of(path),
                Path.of("api").resolve(path),
            ).firstOrNull { Files.exists(it) } ?: error("missing project file: $path")
        return Files.readString(file)
    }
}

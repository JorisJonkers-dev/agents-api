package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AgentRunnerObservabilityContractTest {
    @Test
    fun `runner pod env pins gateway service identity and otlp transport`() {
        // Pod env is built in RunnerPodSpecBuilder after the class split.
        val podSpecBuilder =
            readProjectFile(
                "src/main/kotlin/com/jorisjonkers/personalstack/agents/infrastructure/k8s/" +
                    "RunnerPodSpecBuilder.kt",
            )

        assertThat(podSpecBuilder).contains(
            "EnvVarBuilder().withName(\"OTEL_SERVICE_NAME\").withValue(\"agent-gateway\")",
            "EnvVarBuilder()",
            ".withName(\"OTEL_EXPORTER_OTLP_ENDPOINT\")",
            ".withValue(\"http://alloy.observability.svc.cluster.local:4318\")",
            "EnvVarBuilder().withName(\"OTEL_EXPORTER_OTLP_PROTOCOL\").withValue(\"http/protobuf\")",
        )
    }

    @Test
    fun `runner kubernetes probes remain on healthz`() {
        // Probe configuration lives in RunnerPodSpecBuilder after the class split.
        val podSpecBuilder =
            readProjectFile(
                "src/main/kotlin/com/jorisjonkers/personalstack/agents/infrastructure/k8s/" +
                    "RunnerPodSpecBuilder.kt",
            )

        assertThat(podSpecBuilder).contains(
            ".withNewStartupProbe()",
            ".withNewReadinessProbe()",
            ".withNewLivenessProbe()",
        )
        assertThat(Regex("""\.withPath\("/healthz"\)""").findAll(podSpecBuilder).count()).isEqualTo(3)
    }

    @Test
    @Disabled("agent-runner files live in the agent-runtime repo after the split")
    fun `runner dockerfile downloads otel java agent and preserves entrypoint self tests`() {
        val dockerfile = readProjectFile("../agent-runner/Dockerfile")

        assertThat(dockerfile).contains(
            "FROM eclipse-temurin:25-jre-alpine AS otel",
            "opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar",
            "COPY --from=otel /otel-javaagent.jar /opt/otel-javaagent.jar",
            "AGENT_RUNNER_ENTRYPOINT_SELF_TEST=agent-kit-manifest",
            "AGENT_RUNNER_ENTRYPOINT_SELF_TEST=repo-allow",
            "AGENT_RUNNER_ENTRYPOINT_SELF_TEST=repo-dir",
            "AGENT_RUNNER_ENTRYPOINT_SELF_TEST=speckit-seed",
            "AGENT_RUNNER_ENTRYPOINT_SELF_TEST=otel-resource-attributes",
            "team=agents,service.version=img,deployment.environment=prod",
            "service.version=operator,team=agents,deployment.environment=prod",
        )
    }

    @Test
    @Disabled("agent-runner files live in the agent-runtime repo after the split")
    fun `runner entrypoint launches gateway with otel agent and merges resource attributes`() {
        val entrypoint = readProjectFile("../agent-runner/entrypoint.sh")

        assertThat(entrypoint).contains(
            "append_otel_resource_attribute \"service.version\" \"\${SERVICE_VERSION:-unknown}\"",
            "append_otel_resource_attribute \"deployment.environment\" \"\${DEPLOYMENT_ENVIRONMENT:-unknown}\"",
            "AGENT_RUNNER_ENTRYPOINT_SELF_TEST:-}\" = \"otel-resource-attributes\"",
            "export OTEL_SERVICE_NAME=\"agent-gateway\"",
            "-XX:+UseZGC",
            "-Xmx1g",
            "-javaagent:/opt/otel-javaagent.jar",
            "-jar /opt/agent-gateway.jar",
        )
        assertThat(entrypoint).contains("*\",\${key}=\"*) return ;;")
    }

    private fun readProjectFile(path: String): String {
        val file =
            listOf(
                Path.of(path),
                Path.of("api").resolve(path),
                Path.of("services/agent-runner").resolve(path.removePrefix("../agent-runner/")),
            ).firstOrNull { Files.exists(it) } ?: error("missing project file: $path")
        return Files.readString(file)
    }
}

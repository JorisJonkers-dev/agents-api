package com.jorisjonkers.personalstack.agents.application.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentsApiTelemetryContractTest {
    @Test
    fun `contract defines exact micrometer and prometheus names`() {
        assertThat(AgentsApiTelemetryContract.meters)
            .extracting("micrometerId", "prometheusName", "type", "baseUnit")
            .containsExactly(
                tuple("agents.sessions.active", "agents_sessions_active", TelemetryMeterType.GAUGE, "sessions"),
                tuple(
                    "agents.runner.reprovisions",
                    "agents_runner_reprovisions_total",
                    TelemetryMeterType.COUNTER,
                    "reprovisions",
                ),
                tuple(
                    "agents.session.replay.bytes",
                    "agents_session_replay_bytes_total",
                    TelemetryMeterType.COUNTER,
                    "bytes",
                ),
                tuple(
                    "agents.session.replay.failures",
                    "agents_session_replay_failures_total",
                    TelemetryMeterType.COUNTER,
                    "failures",
                ),
                tuple(
                    "agents.session.attach.attempts",
                    "agents_session_attach_attempts_total",
                    TelemetryMeterType.COUNTER,
                    "attempts",
                ),
                tuple(
                    "agents.session.attach.failures",
                    "agents_session_attach_failures_total",
                    TelemetryMeterType.COUNTER,
                    "failures",
                ),
                tuple("agents.storage.bytes", "agents_storage_bytes", TelemetryMeterType.GAUGE, "bytes"),
                tuple("agents.storage.limit.bytes", "agents_storage_limit_bytes", TelemetryMeterType.GAUGE, "bytes"),
                tuple(
                    "agents.operation.duration",
                    "agents_operation_duration_seconds",
                    TelemetryMeterType.TIMER,
                    "seconds",
                ),
            )
    }

    @Test
    fun `contract exposes only bounded tag values`() {
        val attachAttempts = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.session.attach.attempts")

        assertThat(attachAttempts.allowedTagKeys)
            .containsExactlyInAnyOrder("service", "kind", "run_mode", "outcome", "reason")
        assertThat(attachAttempts.allowedTagValues.getValue("kind"))
            .containsExactlyInAnyOrder("claude", "codex", "shell", "other")
        assertThat(attachAttempts.allowedTagValues.getValue("run_mode"))
            .containsExactlyInAnyOrder("interactive", "headless", "setup", "maintenance", "unknown", "other")
        val activeSessions = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.sessions.active")
        assertThat(activeSessions.allowedTagValues.getValue("status"))
            .contains("running", "stopped", "requested", "started", "failed")
        assertThat(attachAttempts.allowedTagValues.getValue("reason"))
            .contains("none", "not_found", "timeout", "permission_denied", "other")

        val attachFailures = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.session.attach.failures")
        assertThat(attachFailures.allowedTagKeys)
            .containsExactlyInAnyOrder("service", "kind", "run_mode", "reason")
    }

    @Test
    fun `classifiers collapse raw and forbidden values to bounded labels`() {
        assertThat(RunModeLabel.fromRaw("workspace-123/http://repo.example/private").label).isEqualTo("other")
        assertThat(AgentKindLabel.fromRaw("new-cli-vendor").label).isEqualTo("other")
        assertThat(StatusLabel.fromRaw("phase=CrashLoopBackOff pod=runner-abc").label).isEqualTo("other")
        assertThat(OperationLabel.fromRaw("delete-repository-main").label).isEqualTo("other")
        assertThat(ModeLabel.fromRaw("user:alice").label).isEqualTo("other")
        assertThat(OutcomeLabel.fromRaw("repository-url-leaked").label).isEqualTo("unknown")
        assertThat(StorageObjectLabel.fromRaw("/workspace/private/transcript.log").label).isEqualTo("other")
        assertThat(FailureReasonLabel.fromRaw("repository https://example.invalid/private timed out").label)
            .isEqualTo("timeout")
    }

    @Test
    fun `noop telemetry accepts events without side effects`() {
        AgentsApiTelemetry.NOOP.recordAttachAttempt(
            AttachAttemptTelemetry(
                kind = AgentKindLabel.CLAUDE,
                runMode = RunModeLabel.INTERACTIVE,
                outcome = OutcomeLabel.SUCCESS,
            ),
        )
    }

    private fun tuple(
        micrometerId: String,
        prometheusName: String,
        type: TelemetryMeterType,
        baseUnit: String,
    ) = org.assertj.core.groups.Tuple
        .tuple(micrometerId, prometheusName, type, baseUnit)
}

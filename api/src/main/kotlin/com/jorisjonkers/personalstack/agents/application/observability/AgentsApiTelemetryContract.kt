package com.jorisjonkers.personalstack.agents.application.observability

data class TelemetryMeterContract(
    val micrometerId: String,
    val prometheusName: String,
    val type: TelemetryMeterType,
    val baseUnit: String?,
    val description: String,
    val allowedTagKeys: Set<String>,
    val allowedTagValues: Map<String, Set<String>>,
)

enum class TelemetryMeterType {
    COUNTER,
    GAUGE,
    TIMER,
}

object AgentsApiTelemetryContract {
    const val SERVICE = "agents-api"

    object Tags {
        const val SERVICE = "service"
        const val STATUS = "status"
        const val KIND = "kind"
        const val RUN_MODE = "run_mode"
        const val OPERATION = "operation"
        const val MODE = "mode"
        const val OUTCOME = "outcome"
        const val STORAGE_OBJECT = "storage_object"
        const val REASON = "reason"
    }

    val meters =
        listOf(
            TelemetryMeterContract(
                micrometerId = "agents.sessions.active",
                prometheusName = "agents_sessions_active",
                type = TelemetryMeterType.GAUGE,
                baseUnit = "sessions",
                description = "Active durable agent sessions by bounded status, kind, and run mode.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.STATUS, Tags.KIND, Tags.RUN_MODE),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.STATUS to StatusLabel.values().labels(),
                        Tags.KIND to AgentKindLabel.values().labels(),
                        Tags.RUN_MODE to RunModeLabel.values().labels(),
                    ),
            ),
            TelemetryMeterContract(
                micrometerId = "agents.runner.reprovisions",
                prometheusName = "agents_runner_reprovisions_total",
                type = TelemetryMeterType.COUNTER,
                baseUnit = "reprovisions",
                description = "Runner reprovision or restart attempts for durable agent sessions.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.OUTCOME, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.OUTCOME to OutcomeLabel.values().labels(),
                        Tags.REASON to FailureReasonLabel.values().labels(),
                    ),
            ),
            TelemetryMeterContract(
                micrometerId = "agents.session.replay.bytes",
                prometheusName = "agents_session_replay_bytes_total",
                type = TelemetryMeterType.COUNTER,
                baseUnit = "bytes",
                description = "Persisted transcript bytes replayed into durable agent attach flows.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.OUTCOME, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.OUTCOME to OutcomeLabel.values().labels(),
                        Tags.REASON to FailureReasonLabel.values().labels(),
                    ),
            ),
            TelemetryMeterContract(
                micrometerId = "agents.session.replay.failures",
                prometheusName = "agents_session_replay_failures_total",
                type = TelemetryMeterType.COUNTER,
                baseUnit = "failures",
                description = "Persisted transcript replay failures with bounded reasons.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.REASON to FailureReasonLabel.values().labels(),
                    ),
            ),
            TelemetryMeterContract(
                micrometerId = "agents.session.attach.attempts",
                prometheusName = "agents_session_attach_attempts_total",
                type = TelemetryMeterType.COUNTER,
                baseUnit = "attempts",
                description = "Durable session attach attempts and failures with bounded reasons.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.KIND, Tags.RUN_MODE, Tags.OUTCOME, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.KIND to AgentKindLabel.values().labels(),
                        Tags.RUN_MODE to RunModeLabel.values().labels(),
                        Tags.OUTCOME to OutcomeLabel.values().labels(),
                        Tags.REASON to FailureReasonLabel.values().labels(),
                    ),
            ),
            TelemetryMeterContract(
                micrometerId = "agents.session.attach.failures",
                prometheusName = "agents_session_attach_failures_total",
                type = TelemetryMeterType.COUNTER,
                baseUnit = "failures",
                description = "Failed durable session attach attempts with bounded reasons.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.KIND, Tags.RUN_MODE, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.KIND to AgentKindLabel.values().labels(),
                        Tags.RUN_MODE to RunModeLabel.values().labels(),
                        Tags.REASON to FailureReasonLabel.values().labels(),
                    ),
            ),
            TelemetryMeterContract(
                micrometerId = "agents.storage.bytes",
                prometheusName = "agents_storage_bytes",
                type = TelemetryMeterType.GAUGE,
                baseUnit = "bytes",
                description = "Service-observed durable storage usage or capacity by bounded storage object.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.STORAGE_OBJECT, Tags.MODE),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.STORAGE_OBJECT to StorageObjectLabel.values().labels(),
                        Tags.MODE to ModeLabel.values().labels(),
                    ),
            ),
            TelemetryMeterContract(
                micrometerId = "agents.storage.limit.bytes",
                prometheusName = "agents_storage_limit_bytes",
                type = TelemetryMeterType.GAUGE,
                baseUnit = "bytes",
                description = "Configured durable storage capacity by bounded storage object.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.STORAGE_OBJECT, Tags.MODE),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.STORAGE_OBJECT to StorageObjectLabel.values().labels(),
                        Tags.MODE to ModeLabel.values().labels(),
                    ),
            ),
            TelemetryMeterContract(
                micrometerId = "agents.operation.duration",
                prometheusName = "agents_operation_duration_seconds",
                type = TelemetryMeterType.TIMER,
                baseUnit = "seconds",
                description = "Agents API operation duration with bounded operation, mode, and outcome labels.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.OPERATION, Tags.MODE, Tags.OUTCOME, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.OPERATION to OperationLabel.values().labels(),
                        Tags.MODE to ModeLabel.values().labels(),
                        Tags.OUTCOME to OutcomeLabel.values().labels(),
                        Tags.REASON to FailureReasonLabel.values().labels(),
                    ),
            ),
        )

    val byMicrometerId = meters.associateBy { it.micrometerId }

    private fun <T> Array<T>.labels(): Set<String>
        where T : Enum<T>, T : TelemetryLabel =
        map { it.label }.toSet()
}

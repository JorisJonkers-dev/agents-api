package com.jorisjonkers.personalstack.agents.application.observability

interface TelemetryLabel {
    val label: String
}

enum class RunModeLabel(
    override val label: String,
) : TelemetryLabel {
    INTERACTIVE("interactive"),
    HEADLESS("headless"),
    SETUP("setup"),
    MAINTENANCE("maintenance"),
    UNKNOWN("unknown"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(runMode: String?): RunModeLabel =
            when (normalize(runMode)) {
                null -> UNKNOWN
                "interactive", "attach", "attached", "terminal", "websocket" -> INTERACTIVE
                "headless", "job", "batch" -> HEADLESS
                "setup", "bootstrap", "provision" -> SETUP
                "maintenance", "cleanup" -> MAINTENANCE
                else -> OTHER
            }
    }
}

enum class AgentKindLabel(
    override val label: String,
) : TelemetryLabel {
    CLAUDE("claude"),
    CODEX("codex"),
    SHELL("shell"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(kind: String?): AgentKindLabel =
            when (normalize(kind)) {
                "claude" -> CLAUDE
                "codex" -> CODEX
                "shell", "bash" -> SHELL
                else -> OTHER
            }
    }
}

enum class StatusLabel(
    override val label: String,
) : TelemetryLabel {
    PENDING("pending"),
    STARTING("starting"),
    READY("ready"),
    IDLE("idle"),
    RUNNING("running"),
    STOPPED("stopped"),
    REQUESTED("requested"),
    STARTED("started"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    DESTROYED("destroyed"),
    UNKNOWN("unknown"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(status: String?): StatusLabel {
            val normalized = normalize(status) ?: return UNKNOWN
            return STATUS_ALIASES[normalized] ?: OTHER
        }
    }
}

enum class OperationLabel(
    override val label: String,
) : TelemetryLabel {
    START_SESSION("start_session"),
    STOP_SESSION("stop_session"),
    ATTACH_SESSION("attach_session"),
    SEND_INPUT("send_input"),
    STAGE_INPUT("stage_input"),
    RESIZE_SESSION("resize_session"),
    REPLAY_HISTORY("replay_history"),
    REPROVISION_RUNNER("reprovision_runner"),
    STORAGE_SAMPLE("storage_sample"),
    UNKNOWN("unknown"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(operation: String?): OperationLabel =
            when (normalize(operation)) {
                null -> UNKNOWN
                "start", "spawn", "start_session", "create_session" -> START_SESSION
                "stop", "kill", "stop_session" -> STOP_SESSION
                "attach", "attach_session" -> ATTACH_SESSION
                "input", "send", "send_input" -> SEND_INPUT
                "stage", "stage_input" -> STAGE_INPUT
                "resize", "resize_session" -> RESIZE_SESSION
                "replay", "replay_history" -> REPLAY_HISTORY
                "reprovision", "restart", "reprovision_runner" -> REPROVISION_RUNNER
                "storage", "storage_sample" -> STORAGE_SAMPLE
                else -> OTHER
            }
    }
}

enum class ModeLabel(
    override val label: String,
) : TelemetryLabel {
    DURABLE("durable"),
    EPHEMERAL("ephemeral"),
    INTERACTIVE("interactive"),
    HEADLESS("headless"),
    UNKNOWN("unknown"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(mode: String?): ModeLabel =
            when (normalize(mode)) {
                null -> UNKNOWN
                "durable", "persisted" -> DURABLE
                "ephemeral", "temporary" -> EPHEMERAL
                "interactive", "attach", "terminal" -> INTERACTIVE
                "headless", "job", "batch" -> HEADLESS
                else -> OTHER
            }
    }
}

enum class OutcomeLabel(
    override val label: String,
) : TelemetryLabel {
    SUCCESS("success"),
    FAILURE("failure"),
    CANCELLED("cancelled"),
    SKIPPED("skipped"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromRaw(outcome: String?): OutcomeLabel =
            when (normalize(outcome)) {
                null -> UNKNOWN
                "success", "succeeded", "ok", "completed" -> SUCCESS
                "failure", "failed", "error" -> FAILURE
                "cancelled", "canceled" -> CANCELLED
                "skipped", "noop", "no_op" -> SKIPPED
                else -> UNKNOWN
            }
    }
}

enum class StorageObjectLabel(
    override val label: String,
) : TelemetryLabel {
    TRANSCRIPT("transcript"),
    WORKSPACE_PVC("workspace_pvc"),
    STAGED_INPUT("staged_input"),
    RUNNER_STATE("runner_state"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(storageObject: String?): StorageObjectLabel =
            when (normalize(storageObject)) {
                "transcript", "transcripts" -> TRANSCRIPT
                "workspace_pvc", "pvc", "volume" -> WORKSPACE_PVC
                "staged_input", "input" -> STAGED_INPUT
                "runner_state", "state" -> RUNNER_STATE
                else -> OTHER
            }
    }
}

enum class FailureReasonLabel(
    override val label: String,
) : TelemetryLabel {
    NONE("none"),
    NOT_FOUND("not_found"),
    INVALID_REQUEST("invalid_request"),
    UPSTREAM_UNAVAILABLE("upstream_unavailable"),
    TIMEOUT("timeout"),
    PROCESS_EXITED("process_exited"),
    IO_ERROR("io_error"),
    PERMISSION_DENIED("permission_denied"),
    CAPACITY("capacity"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
    OTHER("other"),
    ;

    companion object {
        fun fromRaw(reason: String?): FailureReasonLabel {
            val normalized = normalize(reason) ?: return UNKNOWN
            return FAILURE_REASON_ALIASES[normalized]
                ?: if ("timeout" in normalized || "timed_out" in normalized) TIMEOUT else OTHER
        }
    }
}

private fun normalize(value: String?): String? {
    val trimmed = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.replace('-', '_').replace('.', '_').replace(' ', '_')
}

private val STATUS_ALIASES =
    mapOf(
        "pending" to StatusLabel.PENDING,
        "starting" to StatusLabel.STARTING,
        "ready" to StatusLabel.READY,
        "idle" to StatusLabel.IDLE,
        "running" to StatusLabel.RUNNING,
        "stopped" to StatusLabel.STOPPED,
        "stop" to StatusLabel.STOPPED,
        "requested" to StatusLabel.REQUESTED,
        "request" to StatusLabel.REQUESTED,
        "started" to StatusLabel.STARTED,
        "start" to StatusLabel.STARTED,
        "completed" to StatusLabel.COMPLETED,
        "complete" to StatusLabel.COMPLETED,
        "succeeded" to StatusLabel.COMPLETED,
        "success" to StatusLabel.COMPLETED,
        "failed" to StatusLabel.FAILED,
        "failure" to StatusLabel.FAILED,
        "error" to StatusLabel.FAILED,
        "cancelled" to StatusLabel.CANCELLED,
        "canceled" to StatusLabel.CANCELLED,
        "destroyed" to StatusLabel.DESTROYED,
        "deleted" to StatusLabel.DESTROYED,
    )

private val FAILURE_REASON_ALIASES =
    mapOf(
        "none" to FailureReasonLabel.NONE,
        "success" to FailureReasonLabel.NONE,
        "ok" to FailureReasonLabel.NONE,
        "not_found" to FailureReasonLabel.NOT_FOUND,
        "missing" to FailureReasonLabel.NOT_FOUND,
        "gone" to FailureReasonLabel.NOT_FOUND,
        "invalid" to FailureReasonLabel.INVALID_REQUEST,
        "invalid_request" to FailureReasonLabel.INVALID_REQUEST,
        "bad_request" to FailureReasonLabel.INVALID_REQUEST,
        "unavailable" to FailureReasonLabel.UPSTREAM_UNAVAILABLE,
        "upstream_unavailable" to FailureReasonLabel.UPSTREAM_UNAVAILABLE,
        "process_exited" to FailureReasonLabel.PROCESS_EXITED,
        "exit" to FailureReasonLabel.PROCESS_EXITED,
        "exited" to FailureReasonLabel.PROCESS_EXITED,
        "io" to FailureReasonLabel.IO_ERROR,
        "io_error" to FailureReasonLabel.IO_ERROR,
        "filesystem" to FailureReasonLabel.IO_ERROR,
        "permission" to FailureReasonLabel.PERMISSION_DENIED,
        "permission_denied" to FailureReasonLabel.PERMISSION_DENIED,
        "forbidden" to FailureReasonLabel.PERMISSION_DENIED,
        "capacity" to FailureReasonLabel.CAPACITY,
        "quota" to FailureReasonLabel.CAPACITY,
        "storage_full" to FailureReasonLabel.CAPACITY,
        "cancelled" to FailureReasonLabel.CANCELLED,
        "canceled" to FailureReasonLabel.CANCELLED,
        "unknown" to FailureReasonLabel.UNKNOWN,
    )

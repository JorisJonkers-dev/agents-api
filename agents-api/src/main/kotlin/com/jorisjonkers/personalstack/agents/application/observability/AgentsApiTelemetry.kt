package com.jorisjonkers.personalstack.agents.application.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface AgentsApiTelemetry {
    fun recordActiveSessions(sample: ActiveSessionsSample) = Unit

    fun recordAttachAttempt(event: AttachAttemptTelemetry) = Unit

    fun recordAttachFailure(event: AttachAttemptTelemetry) = Unit

    fun recordReplay(event: ReplayTelemetry) = Unit

    fun recordReplayFailure(event: ReplayTelemetry) = Unit

    fun recordRunnerReprovision(event: RunnerReprovisionTelemetry) = Unit

    fun recordStorage(sample: StorageTelemetrySample) = Unit

    fun recordStorageLimit(sample: StorageTelemetrySample) = Unit

    fun recordOperation(event: OperationTelemetry) = Unit

    companion object {
        val NOOP: AgentsApiTelemetry = NoopAgentsApiTelemetry
    }
}

object NoopAgentsApiTelemetry : AgentsApiTelemetry

data class ActiveSessionsSample(
    val status: StatusLabel,
    val kind: AgentKindLabel,
    val runMode: RunModeLabel,
    val count: Long,
)

data class AttachAttemptTelemetry(
    val kind: AgentKindLabel,
    val runMode: RunModeLabel,
    val outcome: OutcomeLabel,
    val reason: FailureReasonLabel = FailureReasonLabel.NONE,
)

data class ReplayTelemetry(
    val bytes: Long,
    val outcome: OutcomeLabel,
    val reason: FailureReasonLabel = FailureReasonLabel.NONE,
)

data class RunnerReprovisionTelemetry(
    val outcome: OutcomeLabel,
    val reason: FailureReasonLabel = FailureReasonLabel.NONE,
)

data class StorageTelemetrySample(
    val storageObject: StorageObjectLabel,
    val mode: ModeLabel,
    val bytes: Long,
)

data class OperationTelemetry(
    val operation: OperationLabel,
    val mode: ModeLabel,
    val outcome: OutcomeLabel,
    val reason: FailureReasonLabel = FailureReasonLabel.NONE,
    val duration: Duration,
)

@Component
class MicrometerAgentsApiTelemetry(
    private val registry: MeterRegistry,
) : AgentsApiTelemetry {
    private val activeSessionGauges = ConcurrentHashMap<List<String>, AtomicLong>()
    private val storageGauges = ConcurrentHashMap<List<String>, AtomicLong>()
    private val storageLimitGauges = ConcurrentHashMap<List<String>, AtomicLong>()

    override fun recordActiveSessions(sample: ActiveSessionsSample) {
        activeSessionGauges
            .computeIfAbsent(
                listOf(sample.status.label, sample.kind.label, sample.runMode.label),
            ) {
                val state = AtomicLong(0)
                Gauge
                    .builder(activeSessions.micrometerId, state) { value -> value.get().toDouble() }
                    .description(activeSessions.description)
                    .baseUnit(activeSessions.baseUnit)
                    .strongReference(true)
                    .tag(tag.SERVICE, AgentsApiTelemetryContract.SERVICE)
                    .tag(tag.STATUS, sample.status.label)
                    .tag(tag.KIND, sample.kind.label)
                    .tag(tag.RUN_MODE, sample.runMode.label)
                    .register(registry)
                state
            }.set(sample.count)
    }

    override fun recordAttachAttempt(event: AttachAttemptTelemetry) {
        Counter
            .builder(attachAttempts.micrometerId)
            .description(attachAttempts.description)
            .baseUnit(attachAttempts.baseUnit)
            .tag(tag.SERVICE, AgentsApiTelemetryContract.SERVICE)
            .tag(tag.KIND, event.kind.label)
            .tag(tag.RUN_MODE, event.runMode.label)
            .tag(tag.OUTCOME, event.outcome.label)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .increment()
    }

    override fun recordAttachFailure(event: AttachAttemptTelemetry) {
        Counter
            .builder(attachFailures.micrometerId)
            .description(attachFailures.description)
            .baseUnit(attachFailures.baseUnit)
            .tag(tag.SERVICE, AgentsApiTelemetryContract.SERVICE)
            .tag(tag.KIND, event.kind.label)
            .tag(tag.RUN_MODE, event.runMode.label)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .increment()
    }

    override fun recordReplay(event: ReplayTelemetry) {
        Counter
            .builder(replayBytes.micrometerId)
            .description(replayBytes.description)
            .baseUnit(replayBytes.baseUnit)
            .tag(tag.SERVICE, AgentsApiTelemetryContract.SERVICE)
            .tag(tag.OUTCOME, event.outcome.label)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .increment(event.bytes.coerceAtLeast(0).toDouble())
    }

    override fun recordReplayFailure(event: ReplayTelemetry) {
        Counter
            .builder(replayFailures.micrometerId)
            .description(replayFailures.description)
            .baseUnit(replayFailures.baseUnit)
            .tag(tag.SERVICE, AgentsApiTelemetryContract.SERVICE)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .increment()
    }

    override fun recordRunnerReprovision(event: RunnerReprovisionTelemetry) {
        Counter
            .builder(runnerReprovisions.micrometerId)
            .description(runnerReprovisions.description)
            .baseUnit(runnerReprovisions.baseUnit)
            .tag(tag.SERVICE, AgentsApiTelemetryContract.SERVICE)
            .tag(tag.OUTCOME, event.outcome.label)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .increment()
    }

    override fun recordStorage(sample: StorageTelemetrySample) {
        storageGauges
            .computeIfAbsent(
                listOf(sample.storageObject.label, sample.mode.label),
            ) {
                val state = AtomicLong(0)
                Gauge
                    .builder(storageBytes.micrometerId, state) { value -> value.get().toDouble() }
                    .description(storageBytes.description)
                    .baseUnit(storageBytes.baseUnit)
                    .strongReference(true)
                    .tag(tag.SERVICE, AgentsApiTelemetryContract.SERVICE)
                    .tag(tag.STORAGE_OBJECT, sample.storageObject.label)
                    .tag(tag.MODE, sample.mode.label)
                    .register(registry)
                state
            }.set(sample.bytes.coerceAtLeast(0))
    }

    override fun recordStorageLimit(sample: StorageTelemetrySample) {
        storageLimitGauges
            .computeIfAbsent(
                listOf(sample.storageObject.label, sample.mode.label),
            ) {
                val state = AtomicLong(0)
                Gauge
                    .builder(storageLimitBytes.micrometerId, state) { value -> value.get().toDouble() }
                    .description(storageLimitBytes.description)
                    .baseUnit(storageLimitBytes.baseUnit)
                    .strongReference(true)
                    .tag(tag.SERVICE, AgentsApiTelemetryContract.SERVICE)
                    .tag(tag.STORAGE_OBJECT, sample.storageObject.label)
                    .tag(tag.MODE, sample.mode.label)
                    .register(registry)
                state
            }.set(sample.bytes.coerceAtLeast(0))
    }

    override fun recordOperation(event: OperationTelemetry) {
        Timer
            .builder(operationDuration.micrometerId)
            .description(operationDuration.description)
            .tag(tag.SERVICE, AgentsApiTelemetryContract.SERVICE)
            .tag(tag.OPERATION, event.operation.label)
            .tag(tag.MODE, event.mode.label)
            .tag(tag.OUTCOME, event.outcome.label)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .record(event.duration)
    }

    private companion object {
        val tag = AgentsApiTelemetryContract.Tags
        val activeSessions = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.sessions.active")
        val runnerReprovisions = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.runner.reprovisions")
        val replayBytes = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.session.replay.bytes")
        val replayFailures = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.session.replay.failures")
        val attachAttempts = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.session.attach.attempts")
        val attachFailures = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.session.attach.failures")
        val storageBytes = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.storage.bytes")
        val storageLimitBytes = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.storage.limit.bytes")
        val operationDuration = AgentsApiTelemetryContract.byMicrometerId.getValue("agents.operation.duration")
    }
}

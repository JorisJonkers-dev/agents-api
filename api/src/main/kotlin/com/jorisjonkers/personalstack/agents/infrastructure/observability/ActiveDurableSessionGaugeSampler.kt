package com.jorisjonkers.personalstack.agents.infrastructure.observability

import com.jorisjonkers.personalstack.agents.application.observability.ActiveSessionsSample
import com.jorisjonkers.personalstack.agents.application.observability.AgentKindLabel
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.RunModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.StatusLabel
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import jakarta.annotation.PostConstruct
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ActiveDurableSessionGaugeSampler(
    private val dsl: DSLContext,
    private val telemetry: AgentsApiTelemetry,
) {
    @PostConstruct
    fun initialize() {
        refresh()
    }

    @Scheduled(fixedDelayString = "\${agents.observability.active-session-gauge-period-ms:30000}")
    fun refresh() {
        snapshot().forEach(telemetry::recordActiveSessions)
    }

    internal fun snapshot(): List<ActiveSessionsSample> {
        val counts = zeroCounts().toMutableMap()
        fetchRawCounts().forEach { row ->
            val key =
                ActiveSessionGaugeKey(
                    status = StatusLabel.fromRaw(row.status),
                    kind = AgentKindLabel.fromRaw(row.kind),
                    runMode = runModeLabel(row.runMode),
                )
            counts[key] = counts.getValue(key) + row.count
        }
        return counts.map { (key, count) ->
            ActiveSessionsSample(
                status = key.status,
                kind = key.kind,
                runMode = key.runMode,
                count = count,
            )
        }
    }

    private fun zeroCounts(): Map<ActiveSessionGaugeKey, Long> =
        StatusLabel
            .values()
            .flatMap { status ->
                AgentKindLabel.values().flatMap { kind ->
                    RunModeLabel.values().map { runMode ->
                        ActiveSessionGaugeKey(status = status, kind = kind, runMode = runMode) to 0L
                    }
                }
            }.toMap()

    private fun fetchRawCounts(): List<RawSessionCount> =
        dsl
            .select(STATUS, KIND, RUN_MODE, DSL.count().`as`(COUNT_ALIAS))
            .from(WORKSPACE_AGENT_SESSIONS)
            .where(STATUS.`in`(ACTIVE_STATUS_NAMES))
            .groupBy(STATUS, KIND, RUN_MODE)
            .fetch { it.toRawSessionCount() }

    private fun Record.toRawSessionCount(): RawSessionCount =
        RawSessionCount(
            status = this.get("status", String::class.java),
            kind = this.get("kind", String::class.java),
            runMode = this.get("run_mode", String::class.java),
            count = this.get(COUNT_ALIAS, Int::class.java).toLong(),
        )

    private fun runModeLabel(runMode: String?): RunModeLabel =
        when (normalize(runMode)) {
            "interactive", "attach", "attached", "terminal", "websocket" -> RunModeLabel.INTERACTIVE
            "headless", "job", "batch" -> RunModeLabel.HEADLESS
            "setup", "bootstrap", "provision" -> RunModeLabel.SETUP
            "maintenance", "cleanup" -> RunModeLabel.MAINTENANCE
            else -> RunModeLabel.OTHER
        }

    private data class ActiveSessionGaugeKey(
        val status: StatusLabel,
        val kind: AgentKindLabel,
        val runMode: RunModeLabel,
    )

    private data class RawSessionCount(
        val status: String,
        val kind: String,
        val runMode: String?,
        val count: Long,
    )

    private companion object {
        val WORKSPACE_AGENT_SESSIONS = DSL.table("workspace_agent_sessions")
        val STATUS = DSL.field("status", String::class.java)
        val KIND = DSL.field("kind", String::class.java)
        val RUN_MODE = DSL.field("run_mode", String::class.java)
        val ACTIVE_STATUS_NAMES =
            listOf(
                WorkspaceAgentSessionStatus.STARTING.name,
                WorkspaceAgentSessionStatus.RUNNING.name,
            )
        const val COUNT_ALIAS = "session_count"

        fun normalize(value: String?): String? {
            val trimmed = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return trimmed.replace('-', '_').replace('.', '_').replace(' ', '_')
        }
    }
}

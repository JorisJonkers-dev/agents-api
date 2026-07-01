package com.jorisjonkers.personalstack.agents.infrastructure.observability

import com.jorisjonkers.personalstack.agents.application.observability.AgentKindLabel
import com.jorisjonkers.personalstack.agents.application.observability.MicrometerAgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.RunModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.StatusLabel
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockExecuteContext
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test

class ActiveDurableSessionGaugeSamplerTest {
    @Test
    fun `empty database emits zero-valued samples for every allowed label combination`() {
        val registry = SimpleMeterRegistry()
        val sampler = sampler(emptyList(), telemetry = MicrometerAgentsApiTelemetry(registry))

        val samples = sampler.snapshot()
        sampler.refresh()

        assertThat(samples).hasSize(expectedSeriesCount)
        assertThat(samples).allSatisfy { sample -> assertThat(sample.count).isZero() }
        assertThat(samples.map { listOf(it.status, it.kind, it.runMode) }.toSet()).hasSize(expectedSeriesCount)
        assertThat(registry.find("agents.sessions.active").gauges())
            .hasSize(expectedSeriesCount)
            .allSatisfy { gauge -> assertThat(gauge.value()).isZero() }
    }

    @Test
    fun `populated database groups active sessions by bounded labels`() {
        val sampler =
            sampler(
                listOf(
                    raw(status = "RUNNING", kind = "CODEX", runMode = "HEADLESS", count = 2),
                    raw(status = "STARTING", kind = "CLAUDE", runMode = "INTERACTIVE", count = 1),
                ),
            )

        val samples = sampler.snapshot()

        assertThat(samples)
            .filteredOn { it.count > 0 }
            .extracting("status", "kind", "runMode", "count")
            .containsExactlyInAnyOrder(
                tuple(StatusLabel.RUNNING, AgentKindLabel.CODEX, RunModeLabel.HEADLESS, 2L),
                tuple(StatusLabel.STARTING, AgentKindLabel.CLAUDE, RunModeLabel.INTERACTIVE, 1L),
            )
    }

    @Test
    fun `unknown blank and future raw run modes emit as other`() {
        val sampler =
            sampler(
                listOf(
                    raw(status = "RUNNING", kind = "CODEX", runMode = "workspace-123/private-repo", count = 2),
                    raw(status = "RUNNING", kind = "CODEX", runMode = "", count = 1),
                    raw(status = "RUNNING", kind = "CODEX", runMode = null, count = 3),
                ),
            )

        val samples = sampler.snapshot()

        assertThat(samples)
            .filteredOn {
                it.status == StatusLabel.RUNNING &&
                    it.kind == AgentKindLabel.CODEX &&
                    it.runMode == RunModeLabel.OTHER
            }.singleElement()
            .extracting("count")
            .isEqualTo(6L)
    }

    @Test
    fun `registered gauges expose only bounded active session labels`() {
        val registry = SimpleMeterRegistry()
        val telemetry = MicrometerAgentsApiTelemetry(registry)
        val sampler =
            sampler(
                listOf(
                    raw(
                        status = "RUNNING",
                        kind = "CODEX",
                        runMode = "user-alice-workspace-123-repository-private-setup-custom",
                        count = 4,
                    ),
                ),
                telemetry = telemetry,
            )

        sampler.refresh()

        val gauges = registry.find("agents.sessions.active").gauges()
        assertThat(gauges).hasSize(expectedSeriesCount)
        assertThat(gauges).allSatisfy { gauge ->
            assertThat(gauge.id.tags.map { it.key })
                .containsExactlyInAnyOrder("service", "status", "kind", "run_mode")
            assertThat(gauge.id.tags.map { it.key })
                .doesNotContain(
                    "session",
                    "session_id",
                    "workspace",
                    "workspace_id",
                    "setup",
                    "setup_id",
                    "repository",
                    "repository_id",
                    "user",
                    "user_id",
                )
            assertThat(RunModeLabel.values().map { it.label }).contains(gauge.id.getTag("run_mode"))
            assertThat(gauge.id.tags.map { it.value })
                .doesNotContain("user-alice-workspace-123-repository-private-setup-custom")
        }
        assertThat(
            registry
                .find("agents.sessions.active")
                .tag("status", "running")
                .tag("kind", "codex")
                .tag("run_mode", "other")
                .gauge()
                ?.value(),
        ).isEqualTo(4.0)
    }

    private fun sampler(
        rows: List<RawRow>,
        telemetry: MicrometerAgentsApiTelemetry = MicrometerAgentsApiTelemetry(SimpleMeterRegistry()),
    ): ActiveDurableSessionGaugeSampler =
        ActiveDurableSessionGaugeSampler(
            dsl = dsl(rows),
            telemetry = telemetry,
        )

    private fun dsl(rows: List<RawRow>): DSLContext {
        val provider =
            MockDataProvider { context ->
                assertGroupedActiveSessionQuery(context)
                val result = DSL.using(SQLDialect.POSTGRES).newResult(STATUS, KIND, RUN_MODE, COUNT)
                rows.forEach { result.add(it.toRecord()) }
                arrayOf(MockResult(rows.size, result))
            }
        return DSL.using(MockConnection(provider), SQLDialect.POSTGRES)
    }

    private fun RawRow.toRecord() =
        DSL
            .using(SQLDialect.POSTGRES)
            .newRecord(STATUS, KIND, RUN_MODE, COUNT)
            .also {
                it[STATUS] = status
                it[KIND] = kind
                it[RUN_MODE] = runMode
                it[COUNT] = count
            }

    private fun assertGroupedActiveSessionQuery(context: MockExecuteContext) {
        val sql = context.sql().lowercase().replace(Regex("\\s+"), " ")
        assertThat(sql).contains("from workspace_agent_sessions")
        assertThat(sql).contains("where status in")
        assertThat(sql).contains("group by status, kind, run_mode")
        assertThat(sql).doesNotContain(
            "workspace_id",
            "gateway_agent_id",
            "cli_session_id",
            "current_setup_id",
            "pending_setup_id",
            "repository",
            "user",
        )
    }

    private fun raw(
        status: String,
        kind: String,
        runMode: String?,
        count: Int,
    ) = RawRow(
        status = status,
        kind = kind,
        runMode = runMode,
        count = count,
    )

    private data class RawRow(
        val status: String,
        val kind: String,
        val runMode: String?,
        val count: Int,
    )

    private companion object {
        val STATUS = DSL.field("status", String::class.java)
        val KIND = DSL.field("kind", String::class.java)
        val RUN_MODE = DSL.field("run_mode", String::class.java)
        val COUNT = DSL.field("session_count", Int::class.java)

        val expectedSeriesCount =
            StatusLabel.values().size *
                AgentKindLabel.values().size *
                RunModeLabel.values().size

        fun tuple(
            status: StatusLabel,
            kind: AgentKindLabel,
            runMode: RunModeLabel,
            count: Long,
        ): org.assertj.core.groups.Tuple =
            org.assertj.core.groups.Tuple
                .tuple(status, kind, runMode, count)
    }
}

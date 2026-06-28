package com.jorisjonkers.personalstack.agents.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkspaceAgentSessionTest {
    @Test
    fun `bindGatewayAgent flips status and writes gateway id`() {
        val s = base()
        val now = Instant.parse("2026-06-12T09:00:00Z")
        val bound = s.bindGatewayAgent("abc12345", cliSessionId = "native-1", now = now)
        assertThat(bound.gatewayAgentId).isEqualTo("abc12345")
        assertThat(bound.cliSessionId).isEqualTo("native-1")
        assertThat(bound.status).isEqualTo(WorkspaceAgentSessionStatus.RUNNING)
        assertThat(bound.gatewayBoundAt).isEqualTo(now)
    }

    @Test
    fun `markStopped and markFailed flip status`() {
        val s = base().bindGatewayAgent("abc")
        assertThat(s.markStopped().status).isEqualTo(WorkspaceAgentSessionStatus.STOPPED)
        assertThat(s.markFailed().status).isEqualTo(WorkspaceAgentSessionStatus.FAILED)
    }

    @Test
    fun `beginGeneration bumps epoch and generation while clearing current binding`() {
        val s =
            base()
                .copy(epoch = 2, generation = 4)
                .bindGatewayAgent("abc", cliSessionId = "native-1", now = Instant.parse("2026-06-12T09:00:00Z"))

        val next = s.beginGeneration(now = Instant.parse("2026-06-12T09:01:00Z"))

        assertThat(next.stableSessionId).isEqualTo(s.id.value.toString())
        assertThat(next.epoch).isEqualTo(3)
        assertThat(next.generation).isEqualTo(5)
        assertThat(next.gatewayAgentId).isNull()
        assertThat(next.gatewayBoundAt).isNull()
        assertThat(next.status).isEqualTo(WorkspaceAgentSessionStatus.STARTING)
    }

    @Test
    fun `retention and cleanup lifecycle fields are explicit`() {
        val retainedUntil = Instant.parse("2026-06-13T09:00:00Z")
        val cleanupRequestedAt = Instant.parse("2026-06-14T09:00:00Z")

        val stopped = base().bindGatewayAgent("abc").markStopped(retainedUntil = retainedUntil)
        val cleanup = stopped.markCleanupRequested(now = cleanupRequestedAt)

        assertThat(stopped.gatewayAgentId).isNull()
        assertThat(stopped.retainedUntil).isEqualTo(retainedUntil)
        assertThat(cleanup.cleanupRequestedAt).isEqualTo(cleanupRequestedAt)
    }

    @Test
    fun `setup transition stages and promotes pending setup`() {
        val requestedAt = Instant.parse("2026-06-12T09:10:00Z")
        val promotedAt = Instant.parse("2026-06-12T09:15:00Z")
        val pending =
            base()
                .requestSetup(
                    setupId = AgentSetupId("gpu"),
                    setupVersion = AgentSetupVersion(2),
                    now = requestedAt,
                )

        assertThat(pending.pendingSetupId).isEqualTo(AgentSetupId("gpu"))
        assertThat(pending.pendingSetupVersion).isEqualTo(AgentSetupVersion(2))
        assertThat(pending.updatedAt).isEqualTo(requestedAt)

        val promoted = pending.promotePendingSetup(now = promotedAt)

        assertThat(promoted.currentSetupId).isEqualTo(AgentSetupId("gpu"))
        assertThat(promoted.currentSetupVersion).isEqualTo(AgentSetupVersion(2))
        assertThat(promoted.pendingSetupId).isNull()
        assertThat(promoted.updatedAt).isEqualTo(promotedAt)
    }

    private fun base() =
        WorkspaceAgentSession(
            id = WorkspaceAgentSessionId.random(),
            workspaceId = WorkspaceId.random(),
            kind = WorkspaceAgentKind.CLAUDE,
            gatewayAgentId = null,
            status = WorkspaceAgentSessionStatus.STARTING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}

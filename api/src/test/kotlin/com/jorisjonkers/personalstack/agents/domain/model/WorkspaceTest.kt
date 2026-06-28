package com.jorisjonkers.personalstack.agents.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkspaceTest {
    private val now = Instant.parse("2026-05-19T10:00:00Z")

    @Test
    fun `withPodInfo transitions to STARTING and stamps pod fields`() {
        val ws = base()
        val updated =
            ws.withPodInfo(
                podName = "agent-runner-abcdef01",
                pvcName = "workspace-abcdef01",
                gatewayEndpoint = "http://x:8090",
            )
        assertThat(updated.status).isEqualTo(WorkspaceStatus.STARTING)
        assertThat(updated.podName).isEqualTo("agent-runner-abcdef01")
        assertThat(updated.pvcName).isEqualTo("workspace-abcdef01")
        assertThat(updated.gatewayEndpoint).isEqualTo("http://x:8090")
        assertThat(updated.updatedAt).isAfterOrEqualTo(updated.createdAt)
    }

    @Test
    fun `markReady markFailed markDestroyed flip status`() {
        val ws = base().withPodInfo("p", "v", "http://x:8090")
        assertThat(ws.markReady().status).isEqualTo(WorkspaceStatus.READY)
        assertThat(ws.markFailed().status).isEqualTo(WorkspaceStatus.FAILED)
        assertThat(ws.markDestroyed().status).isEqualTo(WorkspaceStatus.DESTROYED)
    }

    @Test
    fun `isRepoBacked reflects repoUrl presence`() {
        assertThat(base().isRepoBacked).isFalse
        assertThat(base().copy(repoUrl = "git@github.com:owner/repo.git").isRepoBacked).isTrue
    }

    @Test
    fun `owner defaults to null for legacy workspaces`() {
        assertThat(base().ownerUserId).isNull()
    }

    @Test
    fun `runner setup restart stages target, promotes it, and tracks generation`() {
        val startedAt = Instant.parse("2026-06-12T10:00:00Z")
        val completedAt = Instant.parse("2026-06-12T10:05:00Z")
        val started =
            base()
                .copy(runnerSetupGeneration = 4)
                .beginRunnerSetupRestart(
                    setupId = AgentSetupId("gpu"),
                    setupVersion = AgentSetupVersion(2),
                    now = startedAt,
                )

        assertThat(started.pendingRunnerSetupId).isEqualTo(AgentSetupId("gpu"))
        assertThat(started.pendingRunnerSetupVersion).isEqualTo(AgentSetupVersion(2))
        assertThat(started.runnerSetupGeneration).isEqualTo(5)
        assertThat(started.runnerSetupOperation).isEqualTo(RunnerSetupOperation.RESTARTING)

        val completed = started.completeRunnerSetupRestart(now = completedAt)

        assertThat(completed.currentRunnerSetupId).isEqualTo(AgentSetupId("gpu"))
        assertThat(completed.currentRunnerSetupVersion).isEqualTo(AgentSetupVersion(2))
        assertThat(completed.pendingRunnerSetupId).isNull()
        assertThat(completed.runnerSetupOperation).isEqualTo(RunnerSetupOperation.IDLE)
        assertThat(completed.runnerSetupOperationUpdatedAt).isEqualTo(completedAt)
    }

    private fun base() =
        Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
}

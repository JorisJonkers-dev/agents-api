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

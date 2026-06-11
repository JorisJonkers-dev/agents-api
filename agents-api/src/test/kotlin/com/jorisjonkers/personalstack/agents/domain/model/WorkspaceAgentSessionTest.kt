package com.jorisjonkers.personalstack.agents.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkspaceAgentSessionTest {
    @Test
    fun `bindGatewayAgent flips status and writes gateway id`() {
        val s = base()
        val bound = s.bindGatewayAgent("abc12345")
        assertThat(bound.gatewayAgentId).isEqualTo("abc12345")
        assertThat(bound.status).isEqualTo(WorkspaceAgentSessionStatus.RUNNING)
    }

    @Test
    fun `markStopped and markFailed flip status`() {
        val s = base().bindGatewayAgent("abc")
        assertThat(s.markStopped().status).isEqualTo(WorkspaceAgentSessionStatus.STOPPED)
        assertThat(s.markFailed().status).isEqualTo(WorkspaceAgentSessionStatus.FAILED)
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

package com.jorisjonkers.personalstack.agents.infrastructure.shell

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class ShellSessionRegistryTest {
    private val registry = ShellSessionRegistry()
    private val ownerWorkspace = WorkspaceId.random()
    private val otherWorkspace = WorkspaceId.random()
    private val session =
        ShellSession(
            gatewayAgentId = "abc12345",
            workspaceId = ownerWorkspace,
            tmuxSessionName = "agent-owner-abc12345",
            cwd = "/workspaces/$ownerWorkspace",
            logFile = Path.of("/workspaces/$ownerWorkspace/.agent-sessions/abc12345.log"),
            createdAt = Instant.now(),
        )

    @Test
    fun `find returns the session for its own workspace`() {
        registry.put(session)

        assertThat(registry.find(ownerWorkspace, session.gatewayAgentId)).isEqualTo(session)
    }

    @Test
    fun `find returns null for a different workspace even though the id is real`() {
        registry.put(session)

        assertThat(registry.find(otherWorkspace, session.gatewayAgentId)).isNull()
    }

    @Test
    fun `find returns null for an id that was never spawned, same as a cross-workspace id`() {
        assertThat(registry.find(ownerWorkspace, "ffffffff")).isNull()
    }

    @Test
    fun `remove from a different workspace does not remove the session`() {
        registry.put(session)

        assertThat(registry.remove(otherWorkspace, session.gatewayAgentId)).isNull()
        assertThat(registry.find(ownerWorkspace, session.gatewayAgentId)).isEqualTo(session)
    }

    @Test
    fun `remove from the owning workspace removes it`() {
        registry.put(session)

        assertThat(registry.remove(ownerWorkspace, session.gatewayAgentId)).isEqualTo(session)
        assertThat(registry.find(ownerWorkspace, session.gatewayAgentId)).isNull()
    }
}

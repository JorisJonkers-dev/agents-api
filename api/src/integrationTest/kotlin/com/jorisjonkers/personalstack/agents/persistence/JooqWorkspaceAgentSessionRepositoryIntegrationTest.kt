package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class JooqWorkspaceAgentSessionRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var workspaces: WorkspaceRepository

    @Autowired
    private lateinit var sessions: WorkspaceAgentSessionRepository

    @Test
    fun `save and findById round-trips current and pending setup`() {
        val session =
            session().requestSetup(
                setupId = AgentSetupId("legacy"),
                setupVersion = AgentSetupVersion.initial(),
            )
        sessions.save(session)

        val loaded = sessions.findById(session.id)

        assertThat(loaded!!.currentSetupId).isEqualTo(AgentSetupId.default())
        assertThat(loaded.currentSetupVersion).isEqualTo(AgentSetupVersion.initial())
        assertThat(loaded.pendingSetupId).isEqualTo(AgentSetupId.legacy())
    }

    @Test
    fun `setup CAS methods stage promote and clear pending setup`() {
        val session = sessions.save(session())

        val staged =
            sessions.setPendingSetupIfCurrent(
                id = session.id,
                expectedCurrentSetupId = AgentSetupId.default(),
                expectedCurrentSetupVersion = AgentSetupVersion.initial(),
                pendingSetupId = AgentSetupId.legacy(),
                pendingSetupVersion = AgentSetupVersion.initial(),
            )
        val staleStage =
            sessions.setPendingSetupIfCurrent(
                id = session.id,
                expectedCurrentSetupId = AgentSetupId("missing"),
                expectedCurrentSetupVersion = AgentSetupVersion.initial(),
                pendingSetupId = AgentSetupId.legacy(),
                pendingSetupVersion = AgentSetupVersion.initial(),
            )

        assertThat(staged).isTrue()
        assertThat(staleStage).isFalse()

        val cleared =
            sessions.clearPendingSetupIfCurrent(
                id = session.id,
                expectedPendingSetupId = AgentSetupId.legacy(),
                expectedPendingSetupVersion = AgentSetupVersion.initial(),
            )
        assertThat(cleared).isTrue()

        sessions.setPendingSetupIfCurrent(
            id = session.id,
            expectedCurrentSetupId = AgentSetupId.default(),
            expectedCurrentSetupVersion = AgentSetupVersion.initial(),
            pendingSetupId = AgentSetupId.legacy(),
            pendingSetupVersion = AgentSetupVersion.initial(),
        )
        val promoted =
            sessions.promotePendingSetupIfCurrent(
                id = session.id,
                expectedPendingSetupId = AgentSetupId.legacy(),
                expectedPendingSetupVersion = AgentSetupVersion.initial(),
            )

        val loaded = sessions.findById(session.id)
        assertThat(promoted).isTrue()
        assertThat(loaded!!.currentSetupId).isEqualTo(AgentSetupId.legacy())
        assertThat(loaded.pendingSetupId).isNull()
    }

    private fun session(): WorkspaceAgentSession {
        val workspace = workspace()
        workspaces.save(workspace)
        val now = Instant.now()
        return WorkspaceAgentSession(
            id = WorkspaceAgentSessionId.random(),
            workspaceId = workspace.id,
            kind = WorkspaceAgentKind.CODEX,
            gatewayAgentId = null,
            status = WorkspaceAgentSessionStatus.STARTING,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun workspace(): Workspace {
        val now = Instant.now()
        return Workspace(
            id = WorkspaceId.random(),
            name = "session-test",
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
}

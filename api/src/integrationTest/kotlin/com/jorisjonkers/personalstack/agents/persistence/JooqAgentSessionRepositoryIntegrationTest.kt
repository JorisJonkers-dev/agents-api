package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class JooqAgentSessionRepositoryIntegrationTest
    @Autowired
    constructor(
        private val workspaces: WorkspaceRepository,
        private val sessions: AgentSessionRepository,
    ) : IntegrationTestBase {
        @Test
        fun saveAndFindByIdRoundTripsCurrentAndPendingSetup() {
            val session =
                session().requestSetup(
                    setupId = AgentSetupId("legacy"),
                    setupVersion = AgentSetupVersion.initial(),
                )
            sessions.save(session)

            val loaded = sessions.findById(session.id).required()

            assertThat(loaded.required().currentSetupId).isEqualTo(AgentSetupId.default())
            assertThat(loaded.currentSetupVersion).isEqualTo(AgentSetupVersion.initial())
            assertThat(loaded.pendingSetupId).isEqualTo(AgentSetupId.legacy())
        }

        @Test
        fun saveAndFindByIdRoundTripsSuspendedStatus() {
            val session = sessions.save(session().copy(status = AgentSessionStatus.SUSPENDED))

            assertThat(sessions.findById(session.id).required().status).isEqualTo(AgentSessionStatus.SUSPENDED)
        }

        @Test
        fun setupCASMethodsStagePromoteAndClearPendingSetup() {
            val session = sessions.save(session())

            val staged =
                sessions.setPendingSetupIfCurrent(
                    AgentSessionRepository.PendingSetupUpdate(
                        id = session.id,
                        expectedCurrentSetupId = AgentSetupId.default(),
                        expectedCurrentSetupVersion = AgentSetupVersion.initial(),
                        pendingSetupId = AgentSetupId.legacy(),
                        pendingSetupVersion = AgentSetupVersion.initial(),
                    ),
                )
            val staleStage =
                sessions.setPendingSetupIfCurrent(
                    AgentSessionRepository.PendingSetupUpdate(
                        id = session.id,
                        expectedCurrentSetupId = AgentSetupId("missing"),
                        expectedCurrentSetupVersion = AgentSetupVersion.initial(),
                        pendingSetupId = AgentSetupId.legacy(),
                        pendingSetupVersion = AgentSetupVersion.initial(),
                    ),
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
                AgentSessionRepository.PendingSetupUpdate(
                    id = session.id,
                    expectedCurrentSetupId = AgentSetupId.default(),
                    expectedCurrentSetupVersion = AgentSetupVersion.initial(),
                    pendingSetupId = AgentSetupId.legacy(),
                    pendingSetupVersion = AgentSetupVersion.initial(),
                ),
            )
            val promoted =
                sessions.promotePendingSetupIfCurrent(
                    id = session.id,
                    expectedPendingSetupId = AgentSetupId.legacy(),
                    expectedPendingSetupVersion = AgentSetupVersion.initial(),
                )

            val loaded = sessions.findById(session.id).required()
            assertThat(promoted).isTrue()
            assertThat(loaded.required().currentSetupId).isEqualTo(AgentSetupId.legacy())
            assertThat(loaded.pendingSetupId).isNull()
        }

        private fun session(): AgentSession {
            val workspace = workspace()
            workspaces.save(workspace)
            val now = Instant.now()
            return AgentSession(
                id = AgentSessionId.random(),
                workspaceId = workspace.id,
                kind = WorkspaceAgentKind.CODEX,
                gatewayAgentId = null,
                status = AgentSessionStatus.STARTING,
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
                status = WorkspaceStatus.PREPARING,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

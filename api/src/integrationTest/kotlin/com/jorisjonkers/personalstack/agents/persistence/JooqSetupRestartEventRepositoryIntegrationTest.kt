package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.SetupRestartEventRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class JooqSetupRestartEventRepositoryIntegrationTest
    @Autowired
    constructor(
        private val workspaces: WorkspaceRepository,
        private val events: SetupRestartEventRepository,
    ) : IntegrationTestBase {
        @Test
        fun saveFindAndCASStatusUpdateSetupRestartEvent() {
            val workspace = workspace()
            workspaces.save(workspace)
            val event = event(workspace.id)
            events.save(event)

            val loaded = events.findById(event.id)
            assertThat(loaded).isEqualTo(event)
            assertThat(events.findAllByWorkspaceId(workspace.id).map { it.id }).contains(event.id)

            val startedAt = Instant.parse("2026-06-12T10:05:00Z")
            val changed =
                events.updateStatusIfCurrent(
                    SetupRestartEventRepository.StatusUpdate(
                        id = event.id,
                        expectedStatus = SetupRestartEventStatus.REQUESTED,
                        status = SetupRestartEventStatus.STARTED,
                        startedAt = startedAt,
                        now = startedAt,
                    ),
                )
            val stale =
                events.updateStatusIfCurrent(
                    SetupRestartEventRepository.StatusUpdate(
                        id = event.id,
                        expectedStatus = SetupRestartEventStatus.REQUESTED,
                        status = SetupRestartEventStatus.COMPLETED,
                        completedAt = Instant.parse("2026-06-12T10:10:00Z"),
                    ),
                )

            assertThat(changed).isTrue()
            assertThat(stale).isFalse()
            assertThat(events.findById(event.id).required().status).isEqualTo(SetupRestartEventStatus.STARTED)
        }

        private fun event(workspaceId: WorkspaceId): SetupRestartEvent {
            val now = Instant.parse("2026-06-12T10:00:00Z")
            return SetupRestartEvent(
                id = UUID.randomUUID(),
                workspaceId = workspaceId,
                sessionId = null,
                fromSetupId = AgentSetupId.default(),
                fromSetupVersion = AgentSetupVersion.initial(),
                toSetupId = AgentSetupId.legacy(),
                toSetupVersion = AgentSetupVersion.initial(),
                status = SetupRestartEventStatus.REQUESTED,
                reason = "operator",
                message = null,
                requestedAt = now,
                startedAt = null,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
            )
        }

        private fun workspace(): Workspace {
            val now = Instant.now()
            return Workspace(
                id = WorkspaceId.random(),
                name = "restart-event-test",
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

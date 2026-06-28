package com.jorisjonkers.personalstack.agents.application.sessionstatus

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionStatusPublisherTest {
    private val now = Instant.parse("2026-06-12T09:00:00Z")
    private val broadcaster = mockk<SessionStatusBroadcaster>()
    private val publisher =
        SessionStatusPublisher(
            broadcaster = broadcaster,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    @AfterEach
    fun tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `publishStatus dispatches immediately outside a transaction`() {
        val sessionId = WorkspaceAgentSessionId.random()
        every { broadcaster.broadcastStatus(any()) } returns Unit

        publisher.publishStatus(sessionId, WorkspaceAgentSessionStatus.RUNNING)

        verify {
            broadcaster.broadcastStatus(
                SessionStatusEvent(
                    sessionId = sessionId.value.toString(),
                    status = "RUNNING",
                    idle = false,
                    ts = now.toString(),
                ),
            )
        }
    }

    @Test
    fun `publishStatus waits until after commit when synchronization is active`() {
        val sessionId = WorkspaceAgentSessionId.random()
        every { broadcaster.broadcastStatus(any()) } returns Unit
        TransactionSynchronizationManager.initSynchronization()

        publisher.publishStatus(sessionId, WorkspaceAgentSessionStatus.STARTING)

        verify(exactly = 0) { broadcaster.broadcastStatus(any()) }
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        verify {
            broadcaster.broadcastStatus(
                match {
                    it.sessionId == sessionId.value.toString() &&
                        it.status == "STARTING" &&
                        !it.idle &&
                        it.ts == now.toString()
                },
            )
        }
    }
}

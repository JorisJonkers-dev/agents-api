package com.jorisjonkers.personalstack.agents.application.sessionstatus

import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock

@Service
class SessionStatusPublisher(
    private val broadcaster: SessionStatusBroadcaster,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun publishStatus(
        session: AgentSession,
        idle: Boolean = false,
    ) {
        publishStatus(session.id, session.status, idle)
    }

    fun publishStatus(
        sessionId: AgentSessionId,
        status: AgentSessionStatus,
        idle: Boolean = false,
    ) {
        dispatchAfterCommitOrNow {
            broadcaster.broadcastStatus(
                SessionStatusEvent(
                    sessionId = sessionId.value.toString(),
                    status = status.name,
                    idle = idle,
                    ts = clock.instant().toString(),
                ),
            )
        }
    }

    fun publishRemove(sessionId: AgentSessionId) {
        dispatchAfterCommitOrNow {
            broadcaster.broadcastRemove(
                SessionRemoveEvent(
                    sessionId = sessionId.value.toString(),
                    ts = clock.instant().toString(),
                ),
            )
        }
    }

    private fun dispatchAfterCommitOrNow(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        action()
                    }
                },
            )
        } else {
            action()
        }
    }
}

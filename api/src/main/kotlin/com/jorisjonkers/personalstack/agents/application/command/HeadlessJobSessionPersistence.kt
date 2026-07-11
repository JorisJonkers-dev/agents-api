package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Isolated persistence helper for the two-phase headless job commit.
 *
 * Extracted into a separate Spring component so that each method is invoked
 * through the proxy — Spring AOP intercepts external bean calls only, so
 * calling @Transactional methods on `this` inside [StartHeadlessJobCommandHandler]
 * would bypass the proxy and lose the REQUIRES_NEW guarantee.
 */
@Component
class HeadlessJobSessionPersistence(
    private val sessions: WorkspaceAgentSessionRepository,
) {
    /**
     * Phase 1: saves the STARTING placeholder before the gateway call.
     * Committed in its own transaction so the row is durable on disk even if
     * the process crashes during the gateway round-trip.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveStartingSession(session: WorkspaceAgentSession): WorkspaceAgentSession = sessions.save(session)

    /**
     * Phase 2: updates the STARTING session with the gateway job id and its
     * reported status. Maps the gateway’s HeadlessStatus so that jobs that
     * complete or fail synchronously (COMPLETED, FAILED, CANCELLED) are not
     * recorded as RUNNING in the DB.
     * Committed in its own transaction independent of the outer call.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persistSession(
        pendingSession: WorkspaceAgentSession,
        job: AgentGatewayClient.HeadlessJob,
    ) {
        sessions.save(
            pendingSession.copy(
                gatewayAgentId = job.id,
                status = gatewayStatusToSessionStatus(job.status),
                updatedAt = Instant.now(),
            ),
        )
    }

    /**
     * Marks the STARTING session as FAILED when the gateway call fails.
     * Prevents STARTING rows from lingering as irreconcilable stubs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSessionFailed(session: WorkspaceAgentSession) {
        sessions.save(session.markFailed(now = Instant.now()))
    }

    private fun gatewayStatusToSessionStatus(
        gatewayStatus: AgentGatewayClient.HeadlessStatus,
    ): WorkspaceAgentSessionStatus =
        when (gatewayStatus) {
            AgentGatewayClient.HeadlessStatus.RUNNING -> WorkspaceAgentSessionStatus.RUNNING
            AgentGatewayClient.HeadlessStatus.COMPLETED -> WorkspaceAgentSessionStatus.STOPPED
            AgentGatewayClient.HeadlessStatus.CANCELLED -> WorkspaceAgentSessionStatus.STOPPED
            AgentGatewayClient.HeadlessStatus.FAILED -> WorkspaceAgentSessionStatus.FAILED
        }
}

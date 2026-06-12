@file:Suppress("LongMethod")

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.rag.LessonAutoCapture
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Component
class StopAgentSessionCommandHandler(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val gateway: AgentGatewayClient,
    private val autoCapture: LessonAutoCapture,
    private val runtime: AgentRuntimeProperties,
    private val sessionStatus: SessionStatusPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
) : CommandHandler<StopAgentSessionCommand> {
    private val log = LoggerFactory.getLogger(StopAgentSessionCommandHandler::class.java)

    @Transactional
    override fun handle(command: StopAgentSessionCommand) {
        val session =
            sessions.findById(command.sessionId)
                ?: run {
                    recordStop(OutcomeLabel.SKIPPED, FailureReasonLabel.NOT_FOUND)
                    return
                }
        val workspace =
            workspaces.findById(session.workspaceId)
                ?: run {
                    recordStop(OutcomeLabel.FAILURE, FailureReasonLabel.NOT_FOUND)
                    error("workspace ${session.workspaceId} missing for session ${session.id}")
                }
        val gatewayId = session.gatewayAgentId
        if (gatewayId != null) {
            runCatching { gateway.stopAgent(workspace, gatewayId) }
                .onFailure {
                    log.warn("gateway stop of session {} agent {} failed: {}", session.id, gatewayId, it.message)
                    recordStop(OutcomeLabel.FAILURE, FailureReasonLabel.UPSTREAM_UNAVAILABLE)
                }
        }
        val now = clock.instant()
        val retainedUntil = now.plusSeconds(runtime.durableSessionRetentionSeconds)
        val stopped =
            sessions.markLifecycleIfGeneration(
                id = session.id,
                expectedGeneration = session.generation,
                status = WorkspaceAgentSessionStatus.STOPPED,
                retainedUntil = retainedUntil,
                clearGatewayBinding = true,
                now = now,
            )
        if (stopped) {
            sessionStatus.publishStatus(session.markStopped(retainedUntil = retainedUntil, now = now))
            recordStop(OutcomeLabel.SUCCESS, FailureReasonLabel.NONE)
        } else {
            recordStop(OutcomeLabel.FAILURE, FailureReasonLabel.OTHER)
        }
        // The stop command is a definitive "this session is done"
        // signal — flush a final auto-capture pass against the
        // full transcript on the way out.
        runCatching { autoCapture.capture(session.id) }
    }

    private fun recordStop(
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
    ) {
        telemetry.recordOperation(
            OperationTelemetry(
                operation = OperationLabel.STOP_SESSION,
                mode = ModeLabel.DURABLE,
                outcome = outcome,
                reason = reason,
                duration = Duration.ZERO,
            ),
        )
    }
}

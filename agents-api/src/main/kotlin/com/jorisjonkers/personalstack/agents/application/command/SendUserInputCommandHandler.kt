package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.rag.ContextBuilder
import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.TurnId
import com.jorisjonkers.personalstack.agents.domain.model.TurnRole
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.TurnRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class SendUserInputCommandHandler(
    private val sessions: WorkspaceAgentSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val turns: TurnRepository,
    private val gateway: AgentGatewayClient,
    private val contextBuilder: ContextBuilder,
) : CommandHandler<SendUserInputCommand> {
    @Transactional
    override fun handle(command: SendUserInputCommand) {
        val session = sessions.findById(command.sessionId) ?: error("session not found: ${command.sessionId}")
        val workspace = workspaces.findById(session.workspaceId) ?: error("workspace missing for session")
        val gatewayAgentId = session.gatewayAgentId ?: error("session not bound to a gateway agent yet")

        // Persist the raw user prompt for transcript fidelity; the
        // RAG augmentation only travels to the agent, not to the
        // stored history (otherwise replays would double-inject
        // outdated context).
        turns.save(
            Turn(
                id = TurnId.random(),
                sessionId = session.id,
                role = TurnRole.USER,
                body = command.text,
                createdAt = Instant.now(),
            ),
        )

        val augmented = contextBuilder.augment(command.text)
        gateway.sendInput(workspace, gatewayAgentId, augmented, command.enter)
    }
}

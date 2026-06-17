package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.rag.ContextBuilder
import com.jorisjonkers.personalstack.agents.application.sessionbinding.EnsureRunnerSessionBoundInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.TurnId
import com.jorisjonkers.personalstack.agents.domain.model.TurnRole
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.TurnRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class SendUserInputCommandHandler(
    private val turns: TurnRepository,
    private val gateway: AgentGatewayClient,
    private val contextBuilder: ContextBuilder,
    private val binding: RunnerSessionBindingService,
) : CommandHandler<SendUserInputCommand> {
    override fun handle(command: SendUserInputCommand) {
        val current = resolveBinding(command)

        // Persist the raw user prompt for transcript fidelity; the
        // RAG augmentation only travels to the agent, not to the
        // stored history (otherwise replays would double-inject
        // outdated context).
        turns.save(
            Turn(
                id = TurnId.random(),
                sessionId = current.session.id,
                role = TurnRole.USER,
                body = command.text,
                createdAt = Instant.now(),
            ),
        )

        val augmented = contextBuilder.augment(command.text)
        gateway.sendInput(current.workspace, current.gatewayAgent.id, augmented, command.enter)
    }

    private fun resolveBinding(command: SendUserInputCommand): RunnerSessionBindingResult.Bound {
        val result =
            binding.ensureBound(
                EnsureRunnerSessionBoundInput(
                    sessionId = command.sessionId,
                ),
            )
        return when (result) {
            is RunnerSessionBindingResult.Bound -> result
            is RunnerSessionBindingResult.Conflict -> error("session generation conflict: ${command.sessionId.value}")
            is RunnerSessionBindingResult.Unavailable ->
                throw AgentRunnerUnavailableException(
                    workspaceId = result.workspaceId,
                    runnerStatus = result.runnerStatus,
                    retryAfterSeconds =
                        result.retryAfterSeconds ?: AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS,
                )
        }
    }
}

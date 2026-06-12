package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBinder
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.StartRunnerSessionBindingInput
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component

/**
 * Starts an interactive agent session by creating epoch 1 through the
 * runner binding service. Runner provisioning, gateway retry, and CAS
 * binding live there so Kubernetes and gateway calls are not made
 * inside this command handler's transaction.
 */
@Component
class StartAgentSessionCommandHandler(
    private val binding: RunnerSessionBindingService,
) : CommandHandler<StartAgentSessionCommand> {
    override fun handle(command: StartAgentSessionCommand) {
        when (
            val result =
                binding.start(
                    StartRunnerSessionBindingInput(
                        workspaceId = command.workspaceId,
                        sessionId = command.sessionId,
                        kind = command.kind,
                    ),
                )
        ) {
            is RunnerSessionBindingResult.Bound -> Unit
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

    companion object {
        const val MAX_SPAWN_ATTEMPTS: Int = RunnerSessionBinder.MAX_SPAWN_ATTEMPTS
        const val BACKOFF_INITIAL_MS: Long = RunnerSessionBinder.BACKOFF_INITIAL_MS
    }
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.sessionbinding.EnsureRunnerSessionBoundInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingService
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import org.springframework.stereotype.Component

@Component
class StageAgentInputCommandHandler(
    private val binding: RunnerSessionBindingService,
    private val gateway: AgentGatewayClient,
) {
    fun handle(command: StageAgentInputCommand): AgentGatewayClient.StagedInput {
        val current =
            when (
                val result =
                    binding.ensureBound(
                        EnsureRunnerSessionBoundInput(
                            sessionId = command.sessionId,
                            workspaceId = command.workspaceId,
                        ),
                    )
            ) {
                is RunnerSessionBindingResult.Bound -> result
                is RunnerSessionBindingResult.Conflict ->
                    error("session generation conflict: ${command.sessionId.value}")
                is RunnerSessionBindingResult.Unavailable ->
                    throw AgentRunnerUnavailableException(
                        workspaceId = result.workspaceId,
                        runnerStatus = result.runnerStatus,
                        retryAfterSeconds =
                            result.retryAfterSeconds ?: AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS,
                    )
            }
        return gateway.stageInput(current.workspace, current.gatewayAgent.id, command.content, command.name)
    }
}

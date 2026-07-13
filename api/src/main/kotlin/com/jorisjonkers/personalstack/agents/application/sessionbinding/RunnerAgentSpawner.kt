package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import org.slf4j.LoggerFactory
import org.springframework.web.client.ResourceAccessException

/**
 * Handles the spawn-with-retry loop for agent gateway sessions. Extracted from
 * RunnerSessionBinder to keep that class below the TooManyFunctions threshold.
 */
internal class RunnerAgentSpawner(
    private val gateway: AgentGatewayClient,
    private val backoffInitialMs: Long,
) {
    private val log = LoggerFactory.getLogger(RunnerAgentSpawner::class.java)

    fun spawnWithRetry(
        workspace: Workspace,
        session: WorkspaceAgentSession,
        continuation: AgentGatewayClient.ContinuationMetadata?,
    ): AgentGatewayClient.GatewayAgent {
        var lastFailure: ResourceAccessException? = null
        repeat(MAX_SPAWN_ATTEMPTS) { attempt ->
            try {
                return gateway.spawnAgent(buildSpawnRequest(workspace, session, continuation))
            } catch (ex: ResourceAccessException) {
                lastFailure = ex
                logRetry(workspace, ex, attempt)
            }
        }
        throw AgentRunnerUnavailableException(
            workspaceId = workspace.id,
            runnerStatus = "ConnectionRefused",
            retryAfterSeconds = AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS,
            cause = lastFailure,
        )
    }

    private fun buildSpawnRequest(
        workspace: Workspace,
        session: WorkspaceAgentSession,
        continuation: AgentGatewayClient.ContinuationMetadata?,
    ) = AgentGatewayClient.SpawnAgentRequest(
        workspace = workspace,
        kind = session.kind,
        stableSessionId = session.id,
        epoch = session.epoch,
        continuation = continuation,
        resumeCliSessionId = session.cliSessionId,
    )

    private fun logRetry(
        workspace: Workspace,
        ex: ResourceAccessException,
        attempt: Int,
    ) {
        val sleepMs = backoffInitialMs * (attempt + 1)
        log.warn(
            "agent spawn attempt {} for workspace {} failed: {} - retrying in {}ms",
            attempt + 1,
            workspace.id.value,
            ex.message,
            sleepMs,
        )
        if (sleepMs > 0) Thread.sleep(sleepMs)
    }

    companion object {
        const val MAX_SPAWN_ATTEMPTS: Int = 3
    }
}

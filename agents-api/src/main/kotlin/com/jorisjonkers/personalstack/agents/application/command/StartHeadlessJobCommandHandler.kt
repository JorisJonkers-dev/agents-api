package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Provisions the runner if needed, submits a one-shot headless job to
 * the gateway, and persists a [WorkspaceAgentSession] to track it.
 *
 * Idle sweep protection (skipping workspaces with running headless jobs)
 * depends on N3's `run_mode` column — activate after N3 merges.
 */
@Component
class StartHeadlessJobCommandHandler(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val gateway: AgentGatewayClient,
    private val orchestrator: AgentRunnerOrchestrator,
) : CommandHandler<StartHeadlessJobCommand> {
    private val log = LoggerFactory.getLogger(StartHeadlessJobCommandHandler::class.java)

    @Transactional
    override fun handle(command: StartHeadlessJobCommand) {
        val workspace =
            workspaces.findById(command.workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${command.workspaceId.value}")
        val healthy = ensureRunnerReady(workspace)
        val job = launchJob(healthy, command)
        persistSession(command, healthy, job)
        log.info("headless job {} launched for workspace {}", job.id, workspace.id.value)
    }

    private fun launchJob(
        workspace: Workspace,
        command: StartHeadlessJobCommand,
    ): AgentGatewayClient.HeadlessJob =
        runCatching {
            gateway.startHeadlessJob(
                workspace = workspace,
                kind = command.kind,
                prompt = command.prompt,
                cliSessionId = null,
                timeoutSeconds = command.timeoutSeconds,
            )
        }.getOrElse { ex ->
            throw AgentRunnerUnavailableException(
                workspaceId = workspace.id,
                runnerStatus = "HeadlessLaunchFailed",
                cause = ex,
            )
        }

    private fun persistSession(
        command: StartHeadlessJobCommand,
        workspace: Workspace,
        job: AgentGatewayClient.HeadlessJob,
    ) {
        val now = Instant.now()
        sessions.save(
            WorkspaceAgentSession(
                id = command.sessionId,
                workspaceId = workspace.id,
                kind = command.kind,
                gatewayAgentId = job.id,
                status = WorkspaceAgentSessionStatus.RUNNING,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Suppress("ThrowsCount", "ReturnCount")
    private fun ensureRunnerReady(workspace: Workspace): Workspace {
        if (gateway.isReady(workspace)) return workspace
        log.info("runner for workspace {} not ready — provisioning for headless", workspace.id.value)
        return runCatching { reprovisionAndWait(workspace) }
            .getOrElse { ex ->
                if (ex is AgentRunnerUnavailableException) throw ex
                throw AgentRunnerUnavailableException(
                    workspaceId = workspace.id,
                    runnerStatus = "ReprovisionFailed",
                    cause = ex,
                )
            }
    }

    private fun reprovisionAndWait(workspace: Workspace): Workspace {
        orchestrator.destroy(workspace)
        val handle = orchestrator.provision(workspace)
        val repointed =
            workspace.withPodInfo(
                podName = handle.podName,
                pvcName = handle.pvcName,
                gatewayEndpoint = handle.gatewayEndpoint,
            )
        workspaces.save(repointed)
        repeat(READINESS_ATTEMPTS) {
            if (gateway.isReady(repointed)) return repointed
            Thread.sleep(READINESS_POLL_MS)
        }
        throw AgentRunnerUnavailableException(
            workspaceId = workspace.id,
            runnerStatus = "NotReady",
        )
    }

    companion object {
        private const val READINESS_ATTEMPTS = 30
        private const val READINESS_POLL_MS = 5_000L
    }
}

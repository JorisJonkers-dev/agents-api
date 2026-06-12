package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.ResourceAccessException
import java.time.Instant

@Component
class RunnerSessionBinder(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val gateway: AgentGatewayClient,
    private val orchestrator: AgentRunnerOrchestrator,
    private val tx: RunnerSessionBindingTransactions,
    private val sessionStatus: SessionStatusPublisher,
    private val backoffInitialMs: Long = BACKOFF_INITIAL_MS,
) : RunnerSessionBindingService {
    private val log = LoggerFactory.getLogger(RunnerSessionBinder::class.java)

    override fun start(request: StartRunnerSessionBindingInput): RunnerSessionBindingResult {
        val workspace =
            workspaces.findById(request.workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${request.workspaceId.value}")
        val ready = ensureRunnerReady(workspace)
        val now = Instant.now()
        val session =
            WorkspaceAgentSession(
                id = request.sessionId,
                workspaceId = ready.workspace.id,
                kind = request.kind,
                gatewayAgentId = null,
                status = WorkspaceAgentSessionStatus.STARTING,
                createdAt = now,
                updatedAt = now,
                epoch = 1,
                generation = 1,
            )
        val saved = sessions.save(session)
        sessionStatus.publishStatus(saved)
        return spawnAndBind(ready.workspace, session, continuation = null, provisioning = ready.provisioning)
    }

    // Restart is a transactional guard chain where early conflicts are observable.
    @Suppress("LongMethod", "ReturnCount")
    override fun restart(request: RestartRunnerSessionBindingInput): RunnerSessionBindingResult {
        val session =
            sessions.findById(request.sessionId)
                ?: return RunnerSessionBindingResult.Conflict(current = null)
        val workspace =
            workspaces.findById(request.workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${request.workspaceId.value}")
        require(session.workspaceId == request.workspaceId) {
            "session does not belong to workspace: ${request.sessionId.value}"
        }
        if (session.generation != request.expectedGeneration) {
            return RunnerSessionBindingResult.Conflict(current = session)
        }

        val starting = session.beginGeneration(nextEpoch = session.epoch + 1)
        if (!tx.beginGeneration(session, starting)) {
            return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
        }

        val ready =
            runCatching { forceProvisionAndWait(workspace) }
                .getOrElse { ex ->
                    tx.markFailed(starting)
                    throw ex
                }
        return spawnAndBind(
            workspace = ready.workspace,
            session = starting,
            continuation =
                AgentGatewayClient.ContinuationMetadata(
                    reason = request.reason ?: "restart",
                    previousEpoch = session.epoch,
                ),
            provisioning = ready.provisioning,
        )
    }

    // Binding recovery has explicit conflict exits to preserve caller semantics.
    @Suppress("LongMethod", "ReturnCount")
    override fun ensureBound(request: EnsureRunnerSessionBoundInput): RunnerSessionBindingResult {
        val session =
            sessions.findById(request.sessionId)
                ?: return RunnerSessionBindingResult.Conflict(current = null)
        val workspaceId = request.workspaceId ?: session.workspaceId
        val workspace =
            workspaces.findById(workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${workspaceId.value}")
        require(session.workspaceId == workspaceId) {
            "session does not belong to workspace: ${request.sessionId.value}"
        }

        val gatewayAgentId = session.gatewayAgentId
        if (
            session.status == WorkspaceAgentSessionStatus.RUNNING &&
            gatewayAgentId != null &&
            gateway.isReady(workspace)
        ) {
            return RunnerSessionBindingResult.Bound(
                workspace = workspace,
                session = session,
                gatewayAgent =
                    AgentGatewayClient.GatewayAgent(
                        id = gatewayAgentId,
                        kind = session.kind,
                        cwd = "/workspace",
                        cliSessionId = session.cliSessionId,
                        stableSessionId = session.stableSessionId,
                        epoch = session.epoch,
                    ),
                provisioning = RunnerProvisioningResult.AlreadyReady,
            )
        }

        require(
            session.status == WorkspaceAgentSessionStatus.RUNNING ||
                session.status == WorkspaceAgentSessionStatus.STARTING,
        ) {
            "session is not running: ${request.sessionId.value}"
        }

        val starting = session.beginGeneration(nextEpoch = session.epoch + 1)
        if (!tx.beginGeneration(session, starting)) {
            return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
        }
        val ready =
            runCatching { ensureRunnerReady(workspace) }
                .getOrElse { ex ->
                    tx.markFailed(starting)
                    throw ex
                }
        return spawnAndBind(
            workspace = ready.workspace,
            session = starting,
            continuation =
                AgentGatewayClient.ContinuationMetadata(
                    reason = "rebind",
                    previousEpoch = session.epoch,
                ),
            provisioning = ready.provisioning,
        )
    }

    private fun spawnAndBind(
        workspace: Workspace,
        session: WorkspaceAgentSession,
        continuation: AgentGatewayClient.ContinuationMetadata?,
        provisioning: RunnerProvisioningResult,
    ): RunnerSessionBindingResult {
        val gatewayAgent =
            runCatching { spawnAgentWithRetry(workspace, session, continuation) }
                .getOrElse { ex ->
                    tx.markFailed(session)
                    throw ex
                }
        if (!tx.bind(session, gatewayAgent)) {
            runCatching { gateway.stopAgent(workspace, gatewayAgent.id) }
            return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
        }
        val bound =
            sessions.findById(session.id)
                ?: session.bindGatewayAgent(gatewayAgent.id, gatewayAgent.cliSessionId)
        return RunnerSessionBindingResult.Bound(
            workspace = workspace,
            session = bound,
            gatewayAgent = gatewayAgent,
            provisioning = provisioning,
        )
    }

    private fun ensureRunnerReady(workspace: Workspace): RunnerReady {
        if (gateway.isReady(workspace)) return RunnerReady(workspace, RunnerProvisioningResult.AlreadyReady)
        log.info(
            "runner for workspace {} (pod {}) did not answer /healthz - re-provisioning a fresh Pod",
            workspace.id.value,
            workspace.podName,
        )
        return forceProvisionAndWait(workspace)
    }

    // Provisioning and readiness persistence share one failure boundary.
    @Suppress("LongMethod")
    private fun forceProvisionAndWait(workspace: Workspace): RunnerReady {
        val handle =
            runCatching {
                orchestrator.scaleDown(workspace)
                orchestrator.provision(workspace)
            }.getOrElse { ex ->
                throw AgentRunnerUnavailableException(
                    workspaceId = workspace.id,
                    runnerStatus = "ReprovisionFailed",
                    cause = ex,
                )
            }
        val repointed =
            workspace.withPodInfo(
                podName = handle.podName,
                pvcName = handle.pvcName,
                gatewayEndpoint = handle.gatewayEndpoint,
            )
        val saved = workspaces.save(repointed)
        log.info("re-provisioned runner for workspace {} as pod {}", workspace.id.value, handle.podName)
        gateRunnerReadinessWithRetry(saved)
        return RunnerReady(
            workspace = saved,
            provisioning =
                RunnerProvisioningResult.Provisioned(
                    podName = handle.podName,
                    pvcName = handle.pvcName,
                    gatewayEndpoint = handle.gatewayEndpoint,
                ),
        )
    }

    private fun gateRunnerReadinessWithRetry(workspace: Workspace) {
        repeat(MAX_SPAWN_ATTEMPTS) { attempt ->
            if (gateway.isReady(workspace)) return
            if (attempt < MAX_SPAWN_ATTEMPTS - 1) {
                val sleepMs = backoffInitialMs * (attempt + 1)
                if (sleepMs > 0) Thread.sleep(sleepMs)
            }
        }
        throw AgentRunnerUnavailableException(
            workspaceId = workspace.id,
            runnerStatus = "NotReady",
        )
    }

    // Retry logging and final unavailable mapping need the same last failure.
    @Suppress("LongMethod")
    private fun spawnAgentWithRetry(
        workspace: Workspace,
        session: WorkspaceAgentSession,
        continuation: AgentGatewayClient.ContinuationMetadata?,
    ): AgentGatewayClient.GatewayAgent {
        var lastFailure: ResourceAccessException? = null
        repeat(MAX_SPAWN_ATTEMPTS) { attempt ->
            try {
                return gateway.spawnAgent(
                    workspace = workspace,
                    kind = session.kind,
                    stableSessionId = session.id,
                    epoch = session.epoch,
                    continuation = continuation,
                )
            } catch (ex: ResourceAccessException) {
                lastFailure = ex
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
        }
        throw AgentRunnerUnavailableException(
            workspaceId = workspace.id,
            runnerStatus = "ConnectionRefused",
            retryAfterSeconds = AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS,
            cause = lastFailure,
        )
    }

    companion object {
        const val MAX_SPAWN_ATTEMPTS: Int = 3
        const val BACKOFF_INITIAL_MS: Long = 1_000
    }

    private data class RunnerReady(
        val workspace: Workspace,
        val provisioning: RunnerProvisioningResult,
    )
}

@Component
class RunnerSessionBindingTransactions(
    private val sessions: WorkspaceAgentSessionRepository,
    private val sessionStatus: SessionStatusPublisher,
) {
    @Transactional
    fun beginGeneration(
        current: WorkspaceAgentSession,
        next: WorkspaceAgentSession,
    ): Boolean {
        val changed =
            sessions.beginGeneration(
                id = current.id,
                expectedGeneration = current.generation,
                nextEpoch = next.epoch,
            )
        if (changed) sessionStatus.publishStatus(next)
        return changed
    }

    @Transactional
    fun bind(
        session: WorkspaceAgentSession,
        gatewayAgent: AgentGatewayClient.GatewayAgent,
    ): Boolean {
        val changed =
            sessions.bindIfGeneration(
                id = session.id,
                expectedGeneration = session.generation,
                gatewayAgentId = gatewayAgent.id,
                cliSessionId = gatewayAgent.cliSessionId,
            )
        if (changed) {
            sessionStatus.publishStatus(session.bindGatewayAgent(gatewayAgent.id, gatewayAgent.cliSessionId))
        }
        return changed
    }

    @Transactional
    fun markFailed(session: WorkspaceAgentSession): Boolean {
        val changed =
            sessions.markLifecycleIfGeneration(
                id = session.id,
                expectedGeneration = session.generation,
                status = WorkspaceAgentSessionStatus.FAILED,
                retainedUntil = session.retainedUntil,
                clearGatewayBinding = true,
            )
        if (changed) sessionStatus.publishStatus(session.markFailed())
        return changed
    }
}

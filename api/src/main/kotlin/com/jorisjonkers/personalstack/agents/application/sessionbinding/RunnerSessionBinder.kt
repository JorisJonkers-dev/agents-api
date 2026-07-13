package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupSelectionService
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerSetupTarget
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
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
import java.time.Instant

@Component
class RunnerSessionBindingDependencies(
    val workspaces: WorkspaceRepository,
    val sessions: WorkspaceAgentSessionRepository,
    val gateway: AgentGatewayClient,
    val orchestrator: AgentRunnerOrchestrator,
    val tx: RunnerSessionBindingTransactions,
)

@Component
class RunnerSessionNotifications(
    val sessionStatus: SessionStatusPublisher,
    val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
)

@Component
class RunnerSetupDependencies(
    val setupSelection: AgentSetupSelectionService,
    val setupValidation: AgentSetupValidationService,
)

@Component
class RunnerSessionBinder(
    bindingDependencies: RunnerSessionBindingDependencies,
    setupDependencies: RunnerSetupDependencies,
    notifications: RunnerSessionNotifications,
    private val backoffInitialMs: Long = BACKOFF_INITIAL_MS,
) : RunnerSessionBindingService {
    private val workspaces = bindingDependencies.workspaces
    private val sessions = bindingDependencies.sessions
    private val gateway = bindingDependencies.gateway
    private val tx = bindingDependencies.tx
    private val sessionStatus = notifications.sessionStatus
    private val metrics = RunnerBindingMetrics(notifications.telemetry)
    private val setupResolver =
        RunnerSetupResolver(
            setupDependencies.setupSelection,
            setupDependencies.setupValidation,
        )
    private val spawner = RunnerAgentSpawner(gateway, backoffInitialMs)
    private val provisioning =
        RunnerProvisioningCoordinator(
            bindingDependencies.orchestrator,
            gateway,
            workspaces,
            metrics,
            backoffInitialMs,
        )
    private val guards = RunnerBindingGuards(provisioning)
    private val log = LoggerFactory.getLogger(RunnerSessionBinder::class.java)

    override fun start(request: StartRunnerSessionBindingInput): RunnerSessionBindingResult =
        metrics.observeBinding(OperationLabel.START_SESSION, ModeLabel.fromRaw(request.runMode)) {
            startInternal(request)
        }

    private fun startInternal(request: StartRunnerSessionBindingInput): RunnerSessionBindingResult {
        val workspace =
            workspaces.findById(request.workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${request.workspaceId.value}")
        val target =
            setupResolver.resolveNewSessionSetup(workspace, request.kind, request.setupId, request.setupVersion)
        guards.checkBindingReadiness(workspace, target)?.let { return it }
        val now = Instant.now()
        val session = newSession(request, workspace, target, now)
        val gatewayAgent = spawner.spawnWithRetry(workspace, session, continuation = null)
        val saved =
            persistBoundSession(workspace, session, gatewayAgent)
                ?: return RunnerSessionBindingResult.Unavailable(
                    workspaceId = workspace.id,
                    runnerStatus = "PersistenceFailed",
                )
        sessionStatus.publishStatus(saved)
        return RunnerSessionBindingResult.Bound(
            workspace = workspace,
            session = saved,
            gatewayAgent = gatewayAgent,
            provisioning = RunnerProvisioningResult.AlreadyReady,
        )
    }

    private fun newSession(
        request: StartRunnerSessionBindingInput,
        workspace: Workspace,
        target: RunnerSetupTarget,
        now: java.time.Instant,
    ) = WorkspaceAgentSession(
        id = request.sessionId,
        workspaceId = workspace.id,
        kind = request.kind,
        gatewayAgentId = null,
        status = WorkspaceAgentSessionStatus.STARTING,
        createdAt = now,
        updatedAt = now,
        runMode = request.runMode,
        epoch = 1,
        generation = 1,
        currentSetupId = target.entry.definition.id,
        currentSetupVersion = target.entry.definition.version,
    )

    private fun persistBoundSession(
        workspace: Workspace,
        session: WorkspaceAgentSession,
        gatewayAgent: AgentGatewayClient.GatewayAgent,
    ): WorkspaceAgentSession? {
        val boundSession = session.bindGatewayAgent(gatewayAgent.id, gatewayAgent.cliSessionId)
        return runCatching { sessions.save(boundSession) }
            .getOrElse { ex ->
                log.warn(
                    "session persistence failed for workspace {} session {} — stopping spawned agent {}",
                    workspace.id.value,
                    session.id.value,
                    gatewayAgent.id,
                    ex,
                )
                runCatching { gateway.stopAgent(workspace, gatewayAgent.id) }
                null
            }
    }

    override fun restart(request: RestartRunnerSessionBindingInput): RunnerSessionBindingResult =
        metrics.observeBinding(OperationLabel.REPROVISION_RUNNER, ModeLabel.INTERACTIVE) {
            restartInternal(request)
        }

    private fun restartInternal(request: RestartRunnerSessionBindingInput): RunnerSessionBindingResult {
        val session =
            sessions.findById(request.sessionId)
                ?: return RunnerSessionBindingResult.Conflict(current = null)
        val workspace =
            workspaces.findById(request.workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${request.workspaceId.value}")
        require(session.workspaceId == request.workspaceId) {
            "session does not belong to workspace: ${request.sessionId.value}"
        }
        if (session.generation != request.expectedGeneration ||
            guards.hasSetupOperationInProgress(session, workspace)
        ) {
            return RunnerSessionBindingResult.Conflict(current = session)
        }
        return performRestart(session, workspace, request)
    }

    private fun performRestart(
        session: WorkspaceAgentSession,
        workspace: Workspace,
        request: RestartRunnerSessionBindingInput,
    ): RunnerSessionBindingResult {
        val target = setupResolver.resolveRestartSetup(workspace, session, request)
        val leasedWorkspace =
            workspace.beginRunnerSetupRestart(
                setupId = target.entry.definition.id,
                setupVersion = target.entry.definition.version,
            )
        if (!tx.beginWorkspaceSetupOperation(workspace, leasedWorkspace)) {
            return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
        }

        val starting =
            session
                .requestSetup(target.entry.definition.id, target.entry.definition.version)
                .beginGeneration(nextEpoch = session.epoch + 1)
        if (!runCatching { tx.beginSetupGeneration(session, starting) }.getOrDefault(false)) {
            tx.failWorkspaceSetupOperation(leasedWorkspace)
            return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
        }

        val ready =
            runCatching {
                provisioning.forceProvisionAndWait(leasedWorkspace, target, leasedWorkspace.runnerSetupGeneration)
            }.getOrElse { ex ->
                tx.markFailed(starting)
                tx.failWorkspaceSetupOperation(leasedWorkspace)
                throw ex
            }
        return completeRestart(session, starting, ready, target, request)
    }

    private fun completeRestart(
        session: WorkspaceAgentSession,
        starting: WorkspaceAgentSession,
        ready: RunnerReady,
        target: RunnerSetupTarget,
        request: RestartRunnerSessionBindingInput,
    ): RunnerSessionBindingResult {
        if (!tx.completeWorkspaceSetupOperation(ready.workspace)) {
            tx.markFailed(starting)
            tx.failWorkspaceSetupOperation(ready.workspace)
            return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
        }
        val promotedWorkspace = ready.workspace.completeRunnerSetupRestart()
        return if (!ready.ready) {
            awaitingRebindOrConflict(starting, promotedWorkspace)
        } else {
            spawnAndBind(
                workspace = promotedWorkspace,
                session = starting,
                continuation =
                    AgentGatewayClient.ContinuationMetadata(
                        reason = request.reason ?: "restart",
                        previousEpoch = session.epoch,
                        fromSetupLabel = session.currentSetupId.value,
                        toSetupLabel = target.entry.definition.id.value,
                    ),
                provisioning = ready.provisioning,
                promotePendingSetup = true,
            )
        }
    }

    // The runner reprovisioned but is not ready yet — a cold image pull
    // onto a freshly-published version can take minutes, far longer than
    // the synchronous readiness window (and any reverse-proxy timeout).
    // Land the session RUNNING+unbound so the next WS attach resumes it
    // via ensureBound once the runner is ready, instead of failing the
    // restart and wedging the workspace while the pod is still booting.
    private fun awaitingRebindOrConflict(
        starting: WorkspaceAgentSession,
        promotedWorkspace: Workspace,
    ): RunnerSessionBindingResult =
        if (!tx.markAwaitingRebind(starting)) {
            RunnerSessionBindingResult.Conflict(current = sessions.findById(starting.id))
        } else {
            RunnerSessionBindingResult.Unavailable(
                workspaceId = promotedWorkspace.id,
                runnerStatus = RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label,
            )
        }

    override fun ensureBound(request: EnsureRunnerSessionBoundInput): RunnerSessionBindingResult =
        metrics.observeBinding(OperationLabel.ATTACH_SESSION, ModeLabel.INTERACTIVE) {
            ensureBoundInternal(request)
        }

    private fun ensureBoundInternal(request: EnsureRunnerSessionBoundInput): RunnerSessionBindingResult {
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
        guards.ensureBoundGuard(session, workspace)?.let { return it }
        val target = setupResolver.resolveSessionSetup(workspace, session)
        require(
            session.status == WorkspaceAgentSessionStatus.RUNNING ||
                session.status == WorkspaceAgentSessionStatus.STARTING,
        ) {
            "session is not running: ${request.sessionId.value}"
        }
        return tryFastPathBound(session, workspace, target)
            ?: checkReadinessAndRebind(session, workspace, target)
    }

    private fun tryFastPathBound(
        session: WorkspaceAgentSession,
        workspace: Workspace,
        target: RunnerSetupTarget,
    ): RunnerSessionBindingResult.Bound? {
        val gatewayAgentId = session.gatewayAgentId ?: return null
        if (session.status != WorkspaceAgentSessionStatus.RUNNING ||
            !provisioning.isRunnerReadyFor(workspace, target)
        ) {
            return null
        }
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

    // Validate runner readiness before bumping generation — no provisioning in interactive binding paths.
    private fun checkReadinessAndRebind(
        session: WorkspaceAgentSession,
        workspace: Workspace,
        target: RunnerSetupTarget,
    ): RunnerSessionBindingResult {
        val unavailableReason =
            when {
                workspace.runnerBootLeaseId != null -> RunnerUnavailableReason.BOOT_LEASE_HELD.label
                !provisioning.isRunnerReadyFor(workspace, target) ->
                    RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label
                else -> null
            }
        if (unavailableReason != null) {
            return RunnerSessionBindingResult.Unavailable(workspaceId = workspace.id, runnerStatus = unavailableReason)
        }
        val starting = session.beginGeneration(nextEpoch = session.epoch + 1)
        if (!tx.beginGeneration(session, starting)) {
            return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
        }
        return spawnAndBind(
            workspace = workspace,
            session = starting,
            continuation =
                AgentGatewayClient.ContinuationMetadata(
                    reason = "rebind",
                    previousEpoch = session.epoch,
                ),
            provisioning = RunnerProvisioningResult.AlreadyReady,
        )
    }

    private fun spawnAndBind(
        workspace: Workspace,
        session: WorkspaceAgentSession,
        continuation: AgentGatewayClient.ContinuationMetadata?,
        provisioning: RunnerProvisioningResult,
        promotePendingSetup: Boolean = false,
    ): RunnerSessionBindingResult {
        val gatewayAgent =
            runCatching {
                metrics.observeStage(OperationLabel.START_SESSION, ModeLabel.INTERACTIVE) {
                    spawner.spawnWithRetry(workspace, session, continuation)
                }
            }.getOrElse { ex ->
                tx.markFailed(session)
                throw ex
            }
        val bound =
            runCatching {
                metrics.observeStage(
                    operation = OperationLabel.ATTACH_SESSION,
                    mode = ModeLabel.INTERACTIVE,
                    outcome = { changed ->
                        if (changed) OutcomeLabel.SUCCESS to FailureReasonLabel.NONE else metrics.bindingConflict()
                    },
                ) {
                    tx.bind(session, gatewayAgent, promotePendingSetup)
                }
            }.getOrElse {
                runCatching { gateway.stopAgent(workspace, gatewayAgent.id) }
                return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
            }
        if (!bound) {
            runCatching { gateway.stopAgent(workspace, gatewayAgent.id) }
            return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
        }
        val boundSession =
            sessions.findById(session.id)
                ?: session
                    .bindGatewayAgent(gatewayAgent.id, gatewayAgent.cliSessionId)
                    .let { if (promotePendingSetup) it.promotePendingSetup() else it }
        return RunnerSessionBindingResult.Bound(
            workspace = workspace,
            session = boundSession,
            gatewayAgent = gatewayAgent,
            provisioning = provisioning,
        )
    }

    companion object {
        const val MAX_SPAWN_ATTEMPTS: Int = RunnerAgentSpawner.MAX_SPAWN_ATTEMPTS
        const val BACKOFF_INITIAL_MS: Long = 1_000
    }
}

@Component
class RunnerSessionBindingTransactions(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val sessionStatus: SessionStatusPublisher,
) {
    private val log = LoggerFactory.getLogger(RunnerSessionBindingTransactions::class.java)

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
    fun beginSetupGeneration(
        current: WorkspaceAgentSession,
        next: WorkspaceAgentSession,
    ): Boolean {
        val pendingId = requireNotNull(next.pendingSetupId) { "pending setup id is required" }
        val pendingVersion = requireNotNull(next.pendingSetupVersion) { "pending setup version is required" }
        val setupChanged =
            sessions.setPendingSetupIfCurrent(
                WorkspaceAgentSessionRepository.PendingSetupUpdate(
                    id = current.id,
                    expectedCurrentSetupId = current.currentSetupId,
                    expectedCurrentSetupVersion = current.currentSetupVersion,
                    pendingSetupId = pendingId,
                    pendingSetupVersion = pendingVersion,
                ),
            )
        if (!setupChanged) return false
        val generationChanged =
            sessions.beginGeneration(
                id = current.id,
                expectedGeneration = current.generation,
                nextEpoch = next.epoch,
            )
        if (!generationChanged) throw BindingRaceException()
        sessionStatus.publishStatus(next)
        return true
    }

    @Transactional
    fun bind(
        session: WorkspaceAgentSession,
        gatewayAgent: AgentGatewayClient.GatewayAgent,
        promotePendingSetup: Boolean = false,
    ): Boolean {
        val changed =
            sessions.bindIfGeneration(
                id = session.id,
                expectedGeneration = session.generation,
                gatewayAgentId = gatewayAgent.id,
                cliSessionId = gatewayAgent.cliSessionId,
            )
        if (!changed) {
            val current = sessions.findById(session.id)
            log.warn(
                "bind CAS miss for session {} expected generation {} — " +
                    "current: generation={} status={} gatewayAgentId={}",
                session.id.value,
                session.generation,
                current?.generation,
                current?.status,
                current?.gatewayAgentId,
            )
        }
        if (changed && promotePendingSetup) {
            val pendingId = requireNotNull(session.pendingSetupId) { "pending setup id is required" }
            val pendingVersion = requireNotNull(session.pendingSetupVersion) { "pending setup version is required" }
            val promoted =
                sessions.promotePendingSetupIfCurrent(
                    id = session.id,
                    expectedPendingSetupId = pendingId,
                    expectedPendingSetupVersion = pendingVersion,
                )
            if (!promoted) throw BindingRaceException()
        }
        if (changed) {
            val bound = session.bindGatewayAgent(gatewayAgent.id, gatewayAgent.cliSessionId)
            sessionStatus.publishStatus(if (promotePendingSetup) bound.promotePendingSetup() else bound)
        }
        return changed
    }

    @Transactional
    fun markFailed(session: WorkspaceAgentSession): Boolean {
        val changed =
            sessions.markLifecycleIfGeneration(
                WorkspaceAgentSessionRepository.LifecycleUpdate(
                    id = session.id,
                    expectedGeneration = session.generation,
                    status = WorkspaceAgentSessionStatus.FAILED,
                    retainedUntil = session.retainedUntil,
                    clearGatewayBinding = true,
                ),
            )
        if (changed && session.pendingSetupId != null && session.pendingSetupVersion != null) {
            sessions.clearPendingSetupIfCurrent(
                id = session.id,
                expectedPendingSetupId = session.pendingSetupId,
                expectedPendingSetupVersion = session.pendingSetupVersion,
            )
        }
        if (changed) sessionStatus.publishStatus(session.markFailed().clearPendingSetup())
        return changed
    }

    @Transactional
    fun markAwaitingRebind(session: WorkspaceAgentSession): Boolean {
        val changed =
            sessions.markLifecycleIfGeneration(
                WorkspaceAgentSessionRepository.LifecycleUpdate(
                    id = session.id,
                    expectedGeneration = session.generation,
                    status = WorkspaceAgentSessionStatus.RUNNING,
                    retainedUntil = null,
                    clearGatewayBinding = true,
                ),
            )
        if (changed && session.pendingSetupId != null && session.pendingSetupVersion != null) {
            val promoted =
                sessions.promotePendingSetupIfCurrent(
                    id = session.id,
                    expectedPendingSetupId = session.pendingSetupId,
                    expectedPendingSetupVersion = session.pendingSetupVersion,
                )
            if (!promoted) throw BindingRaceException()
        }
        if (changed) sessionStatus.publishStatus(session.markAwaitingRebind())
        return changed
    }

    @Transactional
    fun beginWorkspaceSetupOperation(
        current: Workspace,
        next: Workspace,
    ): Boolean =
        workspaces.beginRunnerSetupOperation(
            WorkspaceRepository.RunnerSetupOperationRequest(
                id = current.id,
                expectedGeneration = current.runnerSetupGeneration,
                setupId = requireNotNull(next.pendingRunnerSetupId) { "pending runner setup id is required" },
                setupVersion =
                    requireNotNull(next.pendingRunnerSetupVersion) { "pending runner setup version is required" },
            ),
        )

    @Transactional
    fun completeWorkspaceSetupOperation(workspace: Workspace): Boolean =
        workspaces.completeRunnerSetupOperation(
            id = workspace.id,
            expectedGeneration = workspace.runnerSetupGeneration,
        )

    @Transactional
    fun failWorkspaceSetupOperation(workspace: Workspace): Boolean =
        workspaces.failRunnerSetupOperation(
            id = workspace.id,
            expectedGeneration = workspace.runnerSetupGeneration,
        )

    private class BindingRaceException : RuntimeException()
}

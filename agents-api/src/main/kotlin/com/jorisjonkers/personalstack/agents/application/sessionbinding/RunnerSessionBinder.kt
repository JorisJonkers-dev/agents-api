@file:Suppress("LongMethod", "TooGenericExceptionCaught")

package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.observability.RunnerReprovisionTelemetry
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupSelectionService
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationInput
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerSetupTarget
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupOperation
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
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
import java.io.IOException
import java.time.Duration
import java.time.Instant

@Component
@Suppress("TooManyFunctions", "LargeClass")
class RunnerSessionBinder(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val gateway: AgentGatewayClient,
    private val orchestrator: AgentRunnerOrchestrator,
    private val setupSelection: AgentSetupSelectionService,
    private val setupValidation: AgentSetupValidationService,
    private val tx: RunnerSessionBindingTransactions,
    private val sessionStatus: SessionStatusPublisher,
    private val backoffInitialMs: Long = BACKOFF_INITIAL_MS,
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
) : RunnerSessionBindingService {
    private val log = LoggerFactory.getLogger(RunnerSessionBinder::class.java)

    @Suppress("LongMethod")
    override fun start(request: StartRunnerSessionBindingInput): RunnerSessionBindingResult =
        observeBindingOperation(OperationLabel.START_SESSION, ModeLabel.fromRaw(request.runMode)) {
            startInternal(request)
        }

    private fun startInternal(request: StartRunnerSessionBindingInput): RunnerSessionBindingResult {
        val workspace =
            workspaces.findById(request.workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${request.workspaceId.value}")
        val target = resolveNewSessionSetup(workspace, request.kind, request.setupId, request.setupVersion)
        // Validate readiness before persisting — genuine cold-start returns Unavailable with no row created.
        checkBindingReadiness(workspace, target)?.let { return it }
        val now = Instant.now()
        val session =
            WorkspaceAgentSession(
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
        // Spawn before persisting so a gateway failure leaves no orphaned STARTING row.
        val gatewayAgent = spawnAgentWithRetry(workspace, session, continuation = null)
        // Persist as RUNNING+bound in one write — the UPSERT accepts this state directly.
        val boundSession = session.bindGatewayAgent(gatewayAgent.id, gatewayAgent.cliSessionId)
        val saved =
            runCatching { sessions.save(boundSession) }
                .getOrElse { ex ->
                    log.warn(
                        "session persistence failed for workspace {} session {} — stopping spawned agent {}",
                        workspace.id.value,
                        session.id.value,
                        gatewayAgent.id,
                        ex,
                    )
                    runCatching { gateway.stopAgent(workspace, gatewayAgent.id) }
                    return RunnerSessionBindingResult.Unavailable(
                        workspaceId = workspace.id,
                        runnerStatus = "PersistenceFailed",
                    )
                }
        sessionStatus.publishStatus(saved)
        return RunnerSessionBindingResult.Bound(
            workspace = workspace,
            session = saved,
            gatewayAgent = gatewayAgent,
            provisioning = RunnerProvisioningResult.AlreadyReady,
        )
    }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
    override fun restart(request: RestartRunnerSessionBindingInput): RunnerSessionBindingResult =
        observeBindingOperation(OperationLabel.REPROVISION_RUNNER, ModeLabel.INTERACTIVE) {
            restartInternal(request)
        }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
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
        if (session.generation != request.expectedGeneration) {
            return RunnerSessionBindingResult.Conflict(current = session)
        }
        if (
            session.pendingSetupId != null ||
            session.pendingSetupVersion != null ||
            workspace.pendingRunnerSetupId != null ||
            workspace.pendingRunnerSetupVersion != null ||
            workspace.runnerSetupOperation != RunnerSetupOperation.IDLE
        ) {
            return RunnerSessionBindingResult.Conflict(current = session)
        }

        val target = resolveRestartSetup(workspace, session, request)
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
            runCatching { forceProvisionAndWait(leasedWorkspace, target, leasedWorkspace.runnerSetupGeneration) }
                .getOrElse { ex ->
                    tx.markFailed(starting)
                    tx.failWorkspaceSetupOperation(leasedWorkspace)
                    throw ex
                }
        if (!tx.completeWorkspaceSetupOperation(ready.workspace)) {
            tx.markFailed(starting)
            tx.failWorkspaceSetupOperation(ready.workspace)
            return RunnerSessionBindingResult.Conflict(current = sessions.findById(session.id))
        }
        val promotedWorkspace = ready.workspace.completeRunnerSetupRestart()
        return spawnAndBind(
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

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
    override fun ensureBound(request: EnsureRunnerSessionBoundInput): RunnerSessionBindingResult =
        observeBindingOperation(OperationLabel.ATTACH_SESSION, ModeLabel.INTERACTIVE) {
            ensureBoundInternal(request)
        }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
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
        // Session-level conflict: a restart is already in progress for this session.
        if (session.pendingSetupId != null || session.pendingSetupVersion != null) {
            return RunnerSessionBindingResult.Conflict(current = session)
        }
        // Workspace runner setup in progress → runner not yet available, retry later.
        if (
            workspace.pendingRunnerSetupId != null ||
            workspace.pendingRunnerSetupVersion != null ||
            workspace.runnerSetupOperation != RunnerSetupOperation.IDLE
        ) {
            return RunnerSessionBindingResult.Unavailable(
                workspaceId = workspace.id,
                runnerStatus = RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS.label,
            )
        }
        val target = resolveSessionSetup(workspace, session)

        val gatewayAgentId = session.gatewayAgentId
        if (
            session.status == WorkspaceAgentSessionStatus.RUNNING &&
            gatewayAgentId != null &&
            isRunnerReadyFor(workspace, target)
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

        // Validate runner readiness before bumping generation — no provisioning in interactive binding paths.
        if (workspace.runnerBootLeaseId != null) {
            return RunnerSessionBindingResult.Unavailable(
                workspaceId = workspace.id,
                runnerStatus = RunnerUnavailableReason.BOOT_LEASE_HELD.label,
            )
        }
        if (!isRunnerReadyFor(workspace, target)) {
            return RunnerSessionBindingResult.Unavailable(
                workspaceId = workspace.id,
                runnerStatus = RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label,
            )
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
                observeStageOperation(OperationLabel.START_SESSION, ModeLabel.INTERACTIVE) {
                    spawnAgentWithRetry(workspace, session, continuation)
                }
            }.getOrElse { ex ->
                tx.markFailed(session)
                throw ex
            }
        val bound =
            runCatching {
                observeStageOperation(
                    operation = OperationLabel.ATTACH_SESSION,
                    mode = ModeLabel.INTERACTIVE,
                    outcome = { if (it) OutcomeLabel.SUCCESS to FailureReasonLabel.NONE else bindingConflict() },
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

    @Suppress("ReturnCount")
    private fun checkBindingReadiness(
        workspace: Workspace,
        target: RunnerSetupTarget,
    ): RunnerSessionBindingResult.Unavailable? {
        if (
            workspace.runnerSetupOperation != RunnerSetupOperation.IDLE ||
            workspace.pendingRunnerSetupId != null ||
            workspace.pendingRunnerSetupVersion != null
        ) {
            return RunnerSessionBindingResult.Unavailable(
                workspaceId = workspace.id,
                runnerStatus = RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS.label,
            )
        }
        if (workspace.runnerBootLeaseId != null) {
            return RunnerSessionBindingResult.Unavailable(
                workspaceId = workspace.id,
                runnerStatus = RunnerUnavailableReason.BOOT_LEASE_HELD.label,
            )
        }
        if (!isRunnerReadyFor(workspace, target)) {
            return RunnerSessionBindingResult.Unavailable(
                workspaceId = workspace.id,
                runnerStatus = RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label,
            )
        }
        return null
    }

    private fun isRunnerReadyFor(
        workspace: Workspace,
        target: RunnerSetupTarget,
    ): Boolean {
        val identity = target.spec.identity(workspace.runnerSetupGeneration)
        return orchestrator.isReady(workspace, identity) && gateway.isReady(workspace)
    }

    private fun resolveNewSessionSetup(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        setupId: AgentSetupId?,
        setupVersion: AgentSetupVersion?,
    ): RunnerSetupTarget {
        if (setupId != null || setupVersion != null) {
            require(setupId != null && setupVersion != null) { "setup id and version must be supplied together" }
            return requireValidTarget(workspace, kind, null, setupSelection.requireSelectable(setupId, setupVersion))
        }
        val current =
            runCatching {
                val entry =
                    setupSelection.requireSelectable(
                        workspace.currentRunnerSetupId,
                        workspace.currentRunnerSetupVersion,
                    )
                val result =
                    setupValidation.validate(
                        AgentSetupValidationInput(
                            workspace = workspace,
                            targetId = entry.definition.id,
                            targetVersion = entry.definition.version,
                            agentKind = kind,
                        ),
                    )
                if (result.valid) RunnerSetupTarget(entry, RunnerSetupProvisioningSpec.from(entry.definition)) else null
            }.getOrNull()
        if (current != null) return current
        return requireValidTarget(workspace, kind, null, setupSelection.defaultSelectable())
    }

    private fun resolveRestartSetup(
        workspace: Workspace,
        session: WorkspaceAgentSession,
        request: RestartRunnerSessionBindingInput,
    ): RunnerSetupTarget {
        require((request.targetSetupId == null) == (request.targetSetupVersion == null)) {
            "target setup id and version must be supplied together"
        }
        val targetId = request.targetSetupId ?: session.currentSetupId
        val targetVersion = request.targetSetupVersion ?: session.currentSetupVersion
        return requireValidTarget(
            workspace = workspace,
            kind = session.kind,
            session = session,
            entry = setupSelection.requireSelectable(targetId, targetVersion),
        )
    }

    private fun resolveSessionSetup(
        workspace: Workspace,
        session: WorkspaceAgentSession,
    ): RunnerSetupTarget =
        requireValidTarget(
            workspace = workspace,
            kind = session.kind,
            session = session,
            entry = setupSelection.requireSelectable(session.currentSetupId, session.currentSetupVersion),
        )

    private fun requireValidTarget(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        session: WorkspaceAgentSession?,
        entry: AgentSetupCatalogEntry,
    ): RunnerSetupTarget {
        setupValidation.requireValid(
            AgentSetupValidationInput(
                workspace = workspace,
                targetId = entry.definition.id,
                targetVersion = entry.definition.version,
                session = session,
                agentKind = kind,
            ),
        )
        return RunnerSetupTarget(entry, RunnerSetupProvisioningSpec.from(entry.definition))
    }

    @Suppress("LongMethod")
    private fun forceProvisionAndWait(
        workspace: Workspace,
        target: RunnerSetupTarget,
        runnerGeneration: Long,
    ): RunnerReady {
        val startedAt = System.nanoTime()
        try {
            val handle =
                runCatching {
                    orchestrator.scaleDown(workspace)
                    orchestrator.provision(workspace, target.spec, runnerGeneration)
                }.getOrElse { ex ->
                    log.warn("reprovision of workspace {} failed during scaleDown/provision", workspace.id.value, ex)
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
            gateRunnerReadinessWithRetry(saved, target, runnerGeneration)
            val ready =
                RunnerReady(
                    workspace = saved,
                    provisioning =
                        RunnerProvisioningResult.Provisioned(
                            podName = handle.podName,
                            pvcName = handle.pvcName,
                            gatewayEndpoint = handle.gatewayEndpoint,
                        ),
                )
            telemetry.recordRunnerReprovision(
                RunnerReprovisionTelemetry(
                    outcome = OutcomeLabel.SUCCESS,
                    reason = FailureReasonLabel.NONE,
                ),
            )
            recordOperation(
                operation = OperationLabel.REPROVISION_RUNNER,
                mode = ModeLabel.DURABLE,
                outcome = OutcomeLabel.SUCCESS,
                reason = FailureReasonLabel.NONE,
                startedAt = startedAt,
            )
            return ready
        } catch (ex: Exception) {
            val reason = reasonClass(ex)
            telemetry.recordRunnerReprovision(
                RunnerReprovisionTelemetry(
                    outcome = OutcomeLabel.FAILURE,
                    reason = reason,
                ),
            )
            recordOperation(
                operation = OperationLabel.REPROVISION_RUNNER,
                mode = ModeLabel.DURABLE,
                outcome = OutcomeLabel.FAILURE,
                reason = reason,
                startedAt = startedAt,
            )
            throw ex
        }
    }

    private fun gateRunnerReadinessWithRetry(
        workspace: Workspace,
        target: RunnerSetupTarget,
        runnerGeneration: Long,
    ) {
        val identity = target.spec.identity(runnerGeneration)
        repeat(MAX_SPAWN_ATTEMPTS) { attempt ->
            if (orchestrator.isReady(workspace, identity) && gateway.isReady(workspace)) return
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
                    // Null on a fresh start; on revival this carries the prior
                    // native CLI id so the gateway resumes that conversation
                    // rather than spawning a blank one.
                    resumeCliSessionId = session.cliSessionId,
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

    private fun observeBindingOperation(
        operation: OperationLabel,
        mode: ModeLabel,
        block: () -> RunnerSessionBindingResult,
    ): RunnerSessionBindingResult {
        val startedAt = System.nanoTime()
        try {
            val result = block()
            val (outcome, reason) =
                when (result) {
                    is RunnerSessionBindingResult.Bound -> OutcomeLabel.SUCCESS to FailureReasonLabel.NONE
                    is RunnerSessionBindingResult.Conflict -> bindingConflict()
                    is RunnerSessionBindingResult.Unavailable ->
                        OutcomeLabel.FAILURE to FailureReasonLabel.UPSTREAM_UNAVAILABLE
                }
            recordOperation(operation, mode, outcome, reason, startedAt)
            return result
        } catch (ex: Exception) {
            recordOperation(operation, mode, OutcomeLabel.FAILURE, reasonClass(ex), startedAt)
            throw ex
        }
    }

    private fun <T> observeStageOperation(
        operation: OperationLabel,
        mode: ModeLabel,
        outcome: (T) -> Pair<OutcomeLabel, FailureReasonLabel> = { OutcomeLabel.SUCCESS to FailureReasonLabel.NONE },
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        try {
            val result = block()
            val (resultOutcome, reason) = outcome(result)
            recordOperation(operation, mode, resultOutcome, reason, startedAt)
            return result
        } catch (ex: Exception) {
            recordOperation(operation, mode, OutcomeLabel.FAILURE, reasonClass(ex), startedAt)
            throw ex
        }
    }

    private fun recordOperation(
        operation: OperationLabel,
        mode: ModeLabel,
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
        startedAt: Long,
    ) {
        telemetry.recordOperation(
            OperationTelemetry(
                operation = operation,
                mode = mode,
                outcome = outcome,
                reason = reason,
                duration = Duration.ofNanos((System.nanoTime() - startedAt).coerceAtLeast(0)),
            ),
        )
    }

    private fun bindingConflict(): Pair<OutcomeLabel, FailureReasonLabel> =
        OutcomeLabel.FAILURE to FailureReasonLabel.CAPACITY

    private fun reasonClass(ex: Throwable): FailureReasonLabel =
        when (ex) {
            is AgentSetupValidationException,
            is IllegalArgumentException,
            -> FailureReasonLabel.INVALID_REQUEST

            is AgentRunnerUnavailableException -> FailureReasonLabel.UPSTREAM_UNAVAILABLE
            is IOException -> FailureReasonLabel.IO_ERROR
            else -> FailureReasonLabel.UNKNOWN
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
                id = current.id,
                expectedCurrentSetupId = current.currentSetupId,
                expectedCurrentSetupVersion = current.currentSetupVersion,
                pendingSetupId = pendingId,
                pendingSetupVersion = pendingVersion,
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
                id = session.id,
                expectedGeneration = session.generation,
                status = WorkspaceAgentSessionStatus.FAILED,
                retainedUntil = session.retainedUntil,
                clearGatewayBinding = true,
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
    fun beginWorkspaceSetupOperation(
        current: Workspace,
        next: Workspace,
    ): Boolean =
        workspaces.beginRunnerSetupOperation(
            id = current.id,
            expectedGeneration = current.runnerSetupGeneration,
            setupId = requireNotNull(next.pendingRunnerSetupId) { "pending runner setup id is required" },
            setupVersion =
                requireNotNull(next.pendingRunnerSetupVersion) { "pending runner setup version is required" },
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

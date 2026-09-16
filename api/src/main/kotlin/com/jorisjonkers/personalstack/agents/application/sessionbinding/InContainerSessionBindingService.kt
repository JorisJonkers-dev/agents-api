package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.application.workspace.WorkspaceDirectoryService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.AgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.InContainerAgentGatewayClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Binds a Shell Agent Session running in-container: no runner Pod, no
 * setup catalog, no boot lease — the tmux session either starts or it
 * doesn't, synchronously. [RunnerSessionBinder]'s CAS/generation/setup
 * machinery exists for the Pod path's provisioning races; there is
 * nothing to provision here, so this stays deliberately small.
 *
 * Selected by [RunnerSessionBindingRouter] for a Shell Agent Session in
 * a Scratch Workspace.
 */
@Component
class InContainerSessionBindingService(
    private val workspaces: WorkspaceRepository,
    private val sessions: AgentSessionRepository,
    private val gateway: InContainerAgentGatewayClient,
    private val sessionStatus: SessionStatusPublisher,
    private val directories: WorkspaceDirectoryService,
) : RunnerSessionBindingService {
    private val log = LoggerFactory.getLogger(InContainerSessionBindingService::class.java)

    override fun start(request: StartRunnerSessionBindingInput): RunnerSessionBindingResult {
        val workspace =
            workspaces.findById(request.workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${request.workspaceId.value}")
        require(workspace.kind == WorkspaceKind.SCRATCH) {
            "the in-container binding service only starts sessions in a Scratch Workspace: ${workspace.id.value}"
        }
        require(request.kind == WorkspaceAgentKind.SHELL) {
            "the in-container binding service only starts Shell Agent Sessions; requested kind=${request.kind}"
        }
        val now = Instant.now()
        val session =
            AgentSession(
                id = request.sessionId,
                workspaceId = workspace.id,
                kind = request.kind,
                gatewayAgentId = null,
                status = AgentSessionStatus.STARTING,
                createdAt = now,
                updatedAt = now,
                runMode = request.runMode,
                epoch = 1,
                generation = 1,
            )
        val gatewayAgent =
            gateway.spawnAgent(
                AgentGatewayClient.SpawnAgentRequest(workspace = workspace, kind = request.kind),
            )
        val bound = session.bindGatewayAgent(gatewayAgent.id, gatewayAgent.cliSessionId)
        val saved =
            runCatching { sessions.save(bound) }
                .getOrElse { ex ->
                    log.warn(
                        "session persistence failed for workspace {} session {} — stopping spawned shell agent {}",
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

    // Restart/Resume of a Shell Agent Session (reattaching after the tmux
    // process itself is gone) is Suspended-session territory — out of the
    // tracer-bullet's scope (#62); it lands with the idle/suspend sweep (#65).
    override fun restart(request: RestartRunnerSessionBindingInput): RunnerSessionBindingResult =
        throw UnsupportedOperationException(
            "restart of an in-container Shell Agent Session is not implemented (tracer bullet #62 scope; " +
                "Suspend/Resume for Shell lands with #65)",
        )

    override fun ensureBound(request: EnsureRunnerSessionBoundInput): RunnerSessionBindingResult {
        val session =
            sessions.findById(request.sessionId) ?: return RunnerSessionBindingResult.Conflict(current = null)
        val workspaceId = request.workspaceId ?: session.workspaceId
        val workspace =
            workspaces.findById(workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${workspaceId.value}")
        val gatewayAgentId = session.gatewayAgentId
        // The tmux session is only ever bound at start() and unbound at stop();
        // a RUNNING session with no gatewayAgentId lost its process (a container
        // restart killed tmux) and isn't resumable in this scope — see restart().
        if (gatewayAgentId == null || session.status != AgentSessionStatus.RUNNING) {
            return RunnerSessionBindingResult.Unavailable(
                workspaceId = workspace.id,
                runnerStatus = "ShellSessionNotResumable",
            )
        }
        return RunnerSessionBindingResult.Bound(
            workspace = workspace,
            session = session,
            // The workspace directory is the only cwd a Shell Agent Session
            // ever runs in — nothing else is tracked on the session.
            gatewayAgent =
                AgentGatewayClient.GatewayAgent(
                    id = gatewayAgentId,
                    kind = session.kind,
                    cwd = directories.directoryFor(workspace.id).toString(),
                    cliSessionId = session.cliSessionId,
                    stableSessionId = session.stableSessionId,
                    epoch = session.epoch,
                ),
            provisioning = RunnerProvisioningResult.AlreadyReady,
        )
    }
}

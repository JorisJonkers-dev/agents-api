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
import org.springframework.web.client.ResourceAccessException
import java.time.Instant

/**
 * Three failure modes are surfaced as a typed 503 instead of an opaque
 * 500 / 502:
 *
 * 1. The workspace's runner Pod is not ready yet — `isReady()` polls
 *    the runner's `/healthz` and reports a transport-level failure
 *    before we attempt the real `POST /agents`. Throws
 *    [AgentRunnerUnavailableException] with `runnerStatus="NotReady"`.
 * 2. The runner's Service has Ready endpoints but the spawn HTTP call
 *    still gets `Connection refused` — a race between Endpoints
 *    publish and the runner's HTTP listener binding. The handler
 *    retries the spawn a bounded number of times with backoff before
 *    surfacing `runnerStatus="ConnectionRefused"`.
 * 3. The runner Pod is gone (evicted / deleted) or wedged in
 *    CrashLoopBackOff — typically a workspace created against an older
 *    agent-runner image. Here a `/healthz` poll alone never recovers:
 *    no Pod will ever answer. The handler re-provisions the runner
 *    (an idempotent ensure that lands a fresh Pod pulling the current
 *    `:latest`), re-points the workspace's `gatewayEndpoint`/`podName`,
 *    and only then re-gates readiness. The 503 is surfaced solely when
 *    re-provision itself fails or the fresh Pod still hasn't passed
 *    `/healthz` inside the retry budget.
 *
 * The 503 carries a `Retry-After` header (via
 * [AgentRunnerUnavailableExceptionHandler]) so well-behaved clients
 * back off automatically; the UI consumes the `runnerStatus` /
 * `retryAfterSeconds` ProblemDetail extensions to render a useful
 * inline message instead of a top-right "Internal Server Error" toast.
 */
@Component
class StartAgentSessionCommandHandler(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val gateway: AgentGatewayClient,
    private val orchestrator: AgentRunnerOrchestrator,
    /**
     * Initial backoff between spawn retries. The default matches a
     * production-tuned 1 s; tests pass `0` so the suite doesn't
     * burn 6 s on the worst-case retry scenario.
     */
    private val backoffInitialMs: Long = BACKOFF_INITIAL_MS,
) : CommandHandler<StartAgentSessionCommand> {
    private val log = LoggerFactory.getLogger(StartAgentSessionCommandHandler::class.java)

    @Transactional
    override fun handle(command: StartAgentSessionCommand) {
        val workspace =
            workspaces.findById(command.workspaceId)
                ?: throw NoSuchElementException("workspace not found: ${command.workspaceId.value}")

        val healthy = ensureRunnerReady(workspace)

        val now = Instant.now()
        val session =
            WorkspaceAgentSession(
                id = command.sessionId,
                workspaceId = healthy.id,
                kind = command.kind,
                gatewayAgentId = null,
                status = WorkspaceAgentSessionStatus.STARTING,
                createdAt = now,
                updatedAt = now,
            )
        sessions.save(session)

        val gatewayAgent = spawnAgentWithRetry(healthy, command)
        sessions.save(session.bindGatewayAgent(gatewayAgent.id, gatewayAgent.cliSessionId))
    }

    /**
     * Pre-flight the `/healthz` probe before touching the spawn
     * endpoint so a not-yet-ready runner surfaces as a clean 503
     * rather than a `ResourceAccessException` deep in the RestClient
     * stack.
     *
     * When the runner answers `/healthz` it is left untouched — a
     * healthy Pod is never destroyed. When it does not, the Pod is
     * either still warming up or it is missing / crash-looping; the
     * latter never self-heals on its own, so the runner is
     * re-provisioned (destroy + provision lands a fresh Pod on the
     * current image), the workspace is re-pointed at the new
     * Pod/endpoint, and readiness is re-gated against the fresh Pod.
     *
     * @return the workspace to spawn against — the same instance when
     *   the runner was already healthy, or the re-pointed copy after a
     *   successful re-provision.
     */
    private fun ensureRunnerReady(workspace: Workspace): Workspace {
        if (gateway.isReady(workspace)) return workspace
        log.info(
            "runner for workspace {} (pod {}) did not answer /healthz — re-provisioning a fresh Pod",
            workspace.id.value,
            workspace.podName,
        )
        val reprovisioned = reprovision(workspace)
        gateRunnerReadinessWithRetry(reprovisioned)
        return reprovisioned
    }

    /**
     * Scale down any stale Pod/Service then provision a fresh one.
     * The workspace PVC is preserved so /workspace contents survive the
     * re-provision. `scaleDown` is idempotent (a 404 on a missing Pod
     * is a no-op). A failure to provision is the only thing that turns
     * into a 503 here — the caller can retry.
     */
    private fun reprovision(workspace: Workspace): Workspace {
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
        workspaces.save(repointed)
        log.info("re-provisioned runner for workspace {} as pod {}", workspace.id.value, handle.podName)
        return repointed
    }

    /**
     * Poll `/healthz` against the freshly provisioned Pod within the
     * spawn retry budget. A new Pod takes a few seconds to schedule,
     * pull, and pass its readiness probe; the same bounded backoff the
     * spawn path uses keeps the total wait inside the existing budget
     * before surfacing a `NotReady` 503.
     */
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

    /**
     * Retry the spawn on `ResourceAccessException` (the Spring
     * RestClient wrapping of any transport-level failure: socket
     * refused, read timeout, …). Each attempt sleeps an increasing
     * amount; after [MAX_SPAWN_ATTEMPTS] exhausted attempts the
     * caller gets a 503 instead of a 500.
     */
    private fun spawnAgentWithRetry(
        workspace: Workspace,
        command: StartAgentSessionCommand,
    ): AgentGatewayClient.GatewayAgent {
        var lastFailure: ResourceAccessException? = null
        repeat(MAX_SPAWN_ATTEMPTS) { attempt ->
            try {
                return gateway.spawnAgent(workspace, command.kind)
            } catch (ex: ResourceAccessException) {
                lastFailure = ex
                val sleepMs = backoffInitialMs * (attempt + 1)
                log.warn(
                    "agent spawn attempt {} for workspace {} failed: {} — retrying in {}ms",
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
}

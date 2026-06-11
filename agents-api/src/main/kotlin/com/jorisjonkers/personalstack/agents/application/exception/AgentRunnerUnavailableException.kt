package com.jorisjonkers.personalstack.agents.application.exception

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId

/**
 * The agent-runner Pod for a workspace is not reachable yet.
 *
 * Thrown by [StartAgentSessionCommandHandler] (or by retry logic in
 * [HttpAgentGatewayClient]) when the runner's HTTP listener returns
 * `Connection refused`, or when the Service has no Ready endpoints
 * because the Pod is still scheduling / pulling its image. Mapped
 * by [AgentRunnerUnavailableExceptionHandler] to a 503 ProblemDetail
 * carrying a `Retry-After` header and the structured
 * `runnerStatus` / `retryAfterSeconds` extensions so the UI can
 * render a "runner not ready, retry in 5s" message inline.
 *
 * `runnerStatus` is a short, machine-friendly label of *why* the
 * runner is unavailable. The current vocabulary:
 *
 * * `Pending`           — the Pod has been created but isn't yet
 *   scheduled / hasn't pulled its image.
 * * `NotReady`          — the Pod is Running but its readinessProbe
 *   hasn't passed (the Service has no Ready endpoints).
 * * `ConnectionRefused` — the Service has Ready endpoints but a
 *   POST to `/agents` still gets EOF / Connection refused (race
 *   between Endpoints publish and the HTTP listener binding).
 * * `Unknown`           — fallback when the readiness probe wasn't
 *   reachable to classify the failure.
 */
class AgentRunnerUnavailableException(
    val workspaceId: WorkspaceId,
    val runnerStatus: String,
    val retryAfterSeconds: Int = DEFAULT_RETRY_AFTER_SECONDS,
    cause: Throwable? = null,
) : RuntimeException(
        buildMessage(workspaceId, runnerStatus),
        cause,
    ) {
    companion object {
        const val DEFAULT_RETRY_AFTER_SECONDS: Int = 5

        private fun buildMessage(
            workspaceId: WorkspaceId,
            runnerStatus: String,
        ): String = "agent-runner for workspace ${workspaceId.value} is unavailable (status=$runnerStatus)"
    }
}

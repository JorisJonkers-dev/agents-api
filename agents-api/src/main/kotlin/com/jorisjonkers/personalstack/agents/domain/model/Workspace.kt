package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant

enum class RunnerSetupOperation { IDLE, RESTARTING, FAILED }

/**
 * A Workspace is a long-lived "place where agents work". Three
 * flavours discriminated by [kind]:
 *
 * - `REPO_BACKED` — `repositoryId` points at a [Repository], the
 *   orchestrator clones it and stamps the per-repo deploy key into
 *   a workspace-scoped k8s Secret.
 * - `SCRATCH`     — Pod without a clone. `repositoryId` is null.
 * - `CHAT`        — never actually persisted as a workspace row
 *   today; chat lives in its own [ChatSession] table. The enum
 *   value exists for type-symmetry with the UI tabs.
 *
 * `projectId` is set when the workspace was opened "inside a
 * project context" so the UI can group workspaces by project; it
 * stays null for ad-hoc work.
 *
 * `githubLinkId` is kept around through the M:N migration window so
 * the orchestrator and Vault adapter continue to find the legacy
 * per-link path. New code reads [repositoryId]; a follow-up PR
 * drops the column once every consumer migrates.
 */
data class Workspace(
    val id: WorkspaceId,
    val name: String,
    val repoUrl: String?,
    val branch: String?,
    val podName: String?,
    val pvcName: String?,
    val gatewayEndpoint: String?,
    val status: WorkspaceStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val repositoryId: RepositoryId? = null,
    val projectId: ProjectId? = null,
    val kind: WorkspaceKind = WorkspaceKind.REPO_BACKED,
    @Deprecated(
        "Use repositoryId — kept for the V9 migration window so legacy " +
            "rows + the orchestrator's per-link deploy-key lookup keep working.",
        ReplaceWith("repositoryId"),
    )
    val githubLinkId: GithubLinkId? = null,
    val currentRunnerSetupId: AgentSetupId = AgentSetupId.default(),
    val currentRunnerSetupVersion: AgentSetupVersion = AgentSetupVersion.initial(),
    val pendingRunnerSetupId: AgentSetupId? = null,
    val pendingRunnerSetupVersion: AgentSetupVersion? = null,
    val runnerSetupGeneration: Long = 0,
    val runnerSetupOperation: RunnerSetupOperation = RunnerSetupOperation.IDLE,
    val runnerSetupOperationStartedAt: Instant? = null,
    val runnerSetupOperationUpdatedAt: Instant? = null,
) {
    init {
        require((pendingRunnerSetupId == null) == (pendingRunnerSetupVersion == null)) {
            "pending runner setup id and version must be set together"
        }
        require(runnerSetupGeneration >= 0) { "runner setup generation must be non-negative" }
    }

    val isRepoBacked: Boolean get() = repoUrl != null

    fun withPodInfo(
        podName: String,
        pvcName: String,
        gatewayEndpoint: String,
    ): Workspace =
        copy(
            podName = podName,
            pvcName = pvcName,
            gatewayEndpoint = gatewayEndpoint,
            status = WorkspaceStatus.STARTING,
            updatedAt = Instant.now(),
        )

    fun markReady(): Workspace = copy(status = WorkspaceStatus.READY, updatedAt = Instant.now())

    fun markFailed(): Workspace = copy(status = WorkspaceStatus.FAILED, updatedAt = Instant.now())

    fun markDestroyed(): Workspace = copy(status = WorkspaceStatus.DESTROYED, updatedAt = Instant.now())

    fun beginRunnerSetupRestart(
        setupId: AgentSetupId,
        setupVersion: AgentSetupVersion,
        now: Instant = Instant.now(),
    ): Workspace =
        copy(
            pendingRunnerSetupId = setupId,
            pendingRunnerSetupVersion = setupVersion,
            runnerSetupGeneration = runnerSetupGeneration + 1,
            runnerSetupOperation = RunnerSetupOperation.RESTARTING,
            runnerSetupOperationStartedAt = now,
            runnerSetupOperationUpdatedAt = now,
            updatedAt = now,
        )

    fun completeRunnerSetupRestart(now: Instant = Instant.now()): Workspace {
        val nextId = requireNotNull(pendingRunnerSetupId) { "pending runner setup id is required" }
        val nextVersion = requireNotNull(pendingRunnerSetupVersion) { "pending runner setup version is required" }
        return copy(
            currentRunnerSetupId = nextId,
            currentRunnerSetupVersion = nextVersion,
            pendingRunnerSetupId = null,
            pendingRunnerSetupVersion = null,
            runnerSetupOperation = RunnerSetupOperation.IDLE,
            runnerSetupOperationUpdatedAt = now,
            updatedAt = now,
        )
    }

    fun failRunnerSetupRestart(now: Instant = Instant.now()): Workspace =
        copy(
            runnerSetupOperation = RunnerSetupOperation.FAILED,
            runnerSetupOperationUpdatedAt = now,
            updatedAt = now,
        )
}

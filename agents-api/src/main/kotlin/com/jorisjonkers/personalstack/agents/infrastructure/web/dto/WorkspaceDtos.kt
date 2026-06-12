package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.jorisjonkers.personalstack.agents.application.query.GetWorkspaceQueryService.WorkspaceRepositoryView
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateWorkspaceRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 80)
    val name: String,
    val repoUrl: String? = null,
    val branch: String? = null,
    /**
     * Workspace flavour. Defaults to REPO_BACKED so the existing UI
     * (which doesn't yet send this field) keeps the previous shape.
     */
    val kind: WorkspaceKind = WorkspaceKind.REPO_BACKED,
    /**
     * Optional project context. Set when the workspace was opened
     * from a project's UI; null for ad-hoc work.
     */
    val projectId: UUID? = null,
    /**
     * Preferred way to bind a workspace to a repo + its deploy key.
     * When set, `repoUrl` / `branch` are derived from the
     * Repository row.
     */
    val repositoryId: UUID? = null,
    /**
     * Primary repo for the multi-repository request shape. Kept
     * separate from [repositoryIds] so clients can submit an ordered
     * or unordered selected set without relying on list position.
     * [repositoryId] remains the backwards-compatible alias and wins
     * when both fields match.
     */
    val primaryRepositoryId: UUID? = null,
    /**
     * Selected repositories for a multi-repo workspace. When no
     * primary field is set, the first entry becomes the primary and
     * the remaining distinct ids are attached as extras.
     */
    val repositoryIds: List<UUID>? = null,
    /**
     * Deprecated alias for [repositoryId]. Kept until PR F migrates
     * the agents-ui to the new field. The server prefers
     * [repositoryId] when both are supplied.
     */
    @Deprecated("Use repositoryId", ReplaceWith("repositoryId"))
    val githubLinkId: UUID? = null,
)

data class AttachWorkspaceRepositoryRequest(
    @field:NotNull
    val repositoryId: UUID? = null,
)

@Suppress("DEPRECATION")
data class WorkspaceResponse(
    val id: UUID,
    val name: String,
    val repoUrl: String?,
    val branch: String?,
    val podName: String?,
    val gatewayEndpoint: String?,
    val status: String,
    val kind: String,
    val projectId: UUID?,
    val repositoryId: UUID?,
    val githubLinkId: UUID?,
    val runnerSetup: WorkspaceRunnerSetupResponse,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(w: Workspace) =
            WorkspaceResponse(
                id = w.id.value,
                name = w.name,
                repoUrl = w.repoUrl,
                branch = w.branch,
                podName = w.podName,
                gatewayEndpoint = w.gatewayEndpoint,
                status = w.status.name,
                kind = w.kind.name,
                projectId = w.projectId?.value,
                repositoryId = w.repositoryId?.value,
                githubLinkId = w.githubLinkId?.value,
                runnerSetup = WorkspaceRunnerSetupResponse.of(w),
                createdAt = w.createdAt,
                updatedAt = w.updatedAt,
            )
    }
}

data class WorkspaceRepositoryResponse(
    val id: UUID,
    val name: String,
    @get:JsonProperty("isPrimary")
    val isPrimary: Boolean,
    val attachedAt: Instant,
    val repoUrl: String,
    val defaultBranch: String,
    val vaultKeyPath: String,
    val deployKeyFingerprint: String?,
    val deployKeyAddedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val verification: AccessVerificationResponse?,
) {
    companion object {
        fun of(view: WorkspaceRepositoryView): WorkspaceRepositoryResponse {
            val r = view.repository
            return WorkspaceRepositoryResponse(
                id = r.id.value,
                name = r.name,
                isPrimary = view.isPrimary,
                attachedAt = view.attachedAt,
                repoUrl = r.repoUrl,
                defaultBranch = r.defaultBranch,
                vaultKeyPath = r.vaultKeyPath,
                deployKeyFingerprint = r.deployKeyFingerprint,
                deployKeyAddedAt = r.deployKeyAddedAt,
                createdAt = r.createdAt,
                updatedAt = r.updatedAt,
                verification = r.verification?.let(AccessVerificationResponse::of),
            )
        }

        fun of(r: Repository) =
            WorkspaceRepositoryResponse(
                id = r.id.value,
                name = r.name,
                isPrimary = false,
                attachedAt = r.createdAt,
                repoUrl = r.repoUrl,
                defaultBranch = r.defaultBranch,
                vaultKeyPath = r.vaultKeyPath,
                deployKeyFingerprint = r.deployKeyFingerprint,
                deployKeyAddedAt = r.deployKeyAddedAt,
                createdAt = r.createdAt,
                updatedAt = r.updatedAt,
                verification = r.verification?.let(AccessVerificationResponse::of),
            )
    }
}

@Suppress("DEPRECATION")
data class WorkspaceWithRepositoriesResponse(
    val id: UUID,
    val name: String,
    val repoUrl: String?,
    val branch: String?,
    val podName: String?,
    val gatewayEndpoint: String?,
    val status: String,
    val kind: String,
    val projectId: UUID?,
    val repositoryId: UUID?,
    val githubLinkId: UUID?,
    val repositories: List<WorkspaceRepositoryResponse>,
    val runnerSetup: WorkspaceRunnerSetupResponse,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(
            w: Workspace,
            repositories: List<WorkspaceRepositoryView>,
        ) = WorkspaceWithRepositoriesResponse(
            id = w.id.value,
            name = w.name,
            repoUrl = w.repoUrl,
            branch = w.branch,
            podName = w.podName,
            gatewayEndpoint = w.gatewayEndpoint,
            status = w.status.name,
            kind = w.kind.name,
            projectId = w.projectId?.value,
            repositoryId = w.repositoryId?.value,
            githubLinkId = w.githubLinkId?.value,
            repositories = repositories.map(WorkspaceRepositoryResponse::of),
            runnerSetup = WorkspaceRunnerSetupResponse.of(w),
            createdAt = w.createdAt,
            updatedAt = w.updatedAt,
        )
    }
}

data class WorkspaceDetailResponse(
    val workspace: WorkspaceWithRepositoriesResponse,
    val sessions: List<WorkspaceAgentSessionResponse>,
) {
    companion object {
        fun of(
            workspace: Workspace,
            repositories: List<WorkspaceRepositoryView>,
            sessions: List<WorkspaceAgentSession>,
        ) = WorkspaceDetailResponse(
            workspace = WorkspaceWithRepositoriesResponse.of(workspace, repositories),
            sessions = sessions.map(WorkspaceAgentSessionResponse::of),
        )
    }
}

data class WorkspaceRunnerSetupResponse(
    val current: AgentSetupReferenceResponse,
    val pending: AgentSetupReferenceResponse?,
    val generation: Long,
    val operation: String,
    val operationStartedAt: Instant?,
    val operationUpdatedAt: Instant?,
) {
    companion object {
        fun of(w: Workspace): WorkspaceRunnerSetupResponse =
            WorkspaceRunnerSetupResponse(
                current = AgentSetupReferenceResponse.of(w.currentRunnerSetupId, w.currentRunnerSetupVersion),
                pending =
                    w.pendingRunnerSetupId?.let { id ->
                        AgentSetupReferenceResponse.of(id, requireNotNull(w.pendingRunnerSetupVersion))
                    },
                generation = w.runnerSetupGeneration,
                operation = w.runnerSetupOperation.name,
                operationStartedAt = w.runnerSetupOperationStartedAt,
                operationUpdatedAt = w.runnerSetupOperationUpdatedAt,
            )
    }
}

data class StartAgentSessionRequest(
    val kind: WorkspaceAgentKind,
)

data class RestartAgentSessionHttpRequest(
    val expectedGeneration: Long? = null,
    val expectedEpoch: Long? = null,
    val expectedSetupId: String? = null,
    val expectedSetupVersion: Long? = null,
    val expectedCurrentSetupId: String? = null,
    val expectedCurrentSetupVersion: Long? = null,
    val targetSetupId: String? = null,
    val targetSetupVersion: Long? = null,
)

data class RestartAgentSessionResponse(
    val sessionId: UUID,
    val epoch: Long,
    val generation: Long,
    val status: String,
    val currentSetup: AgentSetupReferenceResponse,
    val pendingSetup: AgentSetupReferenceResponse?,
) {
    companion object {
        fun of(s: WorkspaceAgentSession) =
            RestartAgentSessionResponse(
                sessionId = s.id.value,
                epoch = s.epoch,
                generation = s.generation,
                status = s.status.name,
                currentSetup = AgentSetupReferenceResponse.of(s.currentSetupId, s.currentSetupVersion),
                pendingSetup =
                    s.pendingSetupId?.let { id ->
                        AgentSetupReferenceResponse.of(id, requireNotNull(s.pendingSetupVersion))
                    },
            )
    }
}

data class WorkspaceAgentSessionResponse(
    val id: UUID,
    val workspaceId: UUID,
    val kind: WorkspaceAgentKind,
    val gatewayAgentId: String?,
    val epoch: Long,
    val generation: Long,
    val gatewayBoundAt: Instant?,
    val status: String,
    val idle: Boolean,
    val currentSetup: AgentSetupReferenceResponse,
    val pendingSetup: AgentSetupReferenceResponse?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(s: WorkspaceAgentSession) =
            WorkspaceAgentSessionResponse(
                id = s.id.value,
                workspaceId = s.workspaceId.value,
                kind = s.kind,
                gatewayAgentId = s.gatewayAgentId,
                epoch = s.epoch,
                generation = s.generation,
                gatewayBoundAt = s.gatewayBoundAt,
                status = s.status.name,
                idle = s.status == WorkspaceAgentSessionStatus.RUNNING && s.gatewayAgentId == null,
                currentSetup = AgentSetupReferenceResponse.of(s.currentSetupId, s.currentSetupVersion),
                pendingSetup =
                    s.pendingSetupId?.let { id ->
                        AgentSetupReferenceResponse.of(id, requireNotNull(s.pendingSetupVersion))
                    },
                createdAt = s.createdAt,
                updatedAt = s.updatedAt,
            )
    }
}

data class SendUserInputRequest(
    val text: String,
    val enter: Boolean = true,
)

data class StageInputRequest(
    val content: String,
    val name: String? = null,
)

data class StagedInputResponse(
    val path: String,
    val bytes: Long,
    val name: String,
)

data class TurnResponse(
    val id: UUID,
    val sessionId: UUID,
    val role: String,
    val body: String,
    val createdAt: Instant,
) {
    companion object {
        fun of(t: Turn) =
            TurnResponse(
                id = t.id.value,
                sessionId = t.sessionId.value,
                role = t.role.name,
                body = t.body,
                createdAt = t.createdAt,
            )
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.command.AttachWorkspaceRepositoryCommand
import com.jorisjonkers.personalstack.agents.application.command.CreateWorkspaceCommand
import com.jorisjonkers.personalstack.agents.application.command.DestroyWorkspaceCommand
import com.jorisjonkers.personalstack.agents.application.command.DetachWorkspaceRepositoryCommand
import com.jorisjonkers.personalstack.agents.application.query.GetWorkspaceQueryService
import com.jorisjonkers.personalstack.agents.application.query.ListWorkspacesQueryService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService.BootOutcome
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService.BootProvisioningOutcome
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AttachWorkspaceRepositoryRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.CreateWorkspaceRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.WorkspaceConnectResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.WorkspaceDetailResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.WorkspaceResponse
import com.jorisjonkers.personalstack.common.command.CommandBus
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspaceController(
    private val commandBus: CommandBus,
    private val listQuery: ListWorkspacesQueryService,
    private val getQuery: GetWorkspaceQueryService,
    private val lifecycleService: WorkspaceRunnerLifecycleService,
) {
    @Suppress("DEPRECATION")
    @PostMapping
    fun create(
        @Valid @RequestBody req: CreateWorkspaceRequest,
    ): ResponseEntity<WorkspaceResponse> {
        val id = WorkspaceId.random()
        val primaryRepositoryId = primaryRepositoryId(req)
        commandBus.dispatch(
            CreateWorkspaceCommand(
                workspaceId = id,
                name = req.name,
                repoUrl = req.repoUrl,
                branch = req.branch,
                kind = req.kind,
                projectId = req.projectId?.let { ProjectId(it) },
                repositoryId = primaryRepositoryId?.let { RepositoryId(it) },
                repositoryIds = selectedRepositoryIds(req, primaryRepositoryId).map { RepositoryId(it) },
                githubLinkId = req.githubLinkId?.let { GithubLinkId(it) },
            ),
        )
        val view =
            getQuery.getSummary(id)
                ?: throw IllegalStateException(
                    "Workspace $id was created but not yet visible to the read model; retry the GET in a moment",
                )
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceResponse.of(view))
    }

    @GetMapping
    fun list(): List<WorkspaceResponse> = listQuery.listActive().map(WorkspaceResponse::of)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<WorkspaceDetailResponse> {
        val view = getQuery.get(WorkspaceId(id)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(WorkspaceDetailResponse.of(view.workspace, view.repositories, view.sessions))
    }

    @PostMapping("/{id}/connect")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Runner is already ready"),
        ApiResponse(responseCode = "202", description = "Runner boot in progress or just provisioned"),
        ApiResponse(responseCode = "404", description = "Workspace not found"),
        ApiResponse(responseCode = "503", description = "Runner unavailable"),
    )
    fun connect(
        @PathVariable id: UUID,
    ): ResponseEntity<WorkspaceConnectResponse> {
        val outcome = lifecycleService.boot(WorkspaceId(id), WorkspaceAgentKind.CLAUDE)
        return when (outcome) {
            is BootOutcome.Ready -> {
                val snapshot = lifecycleService.readinessSnapshot(outcome.workspace)
                val body = WorkspaceConnectResponse.of(snapshot)
                if (outcome.provisioning is BootProvisioningOutcome.AlreadyReady) {
                    ResponseEntity.ok(body)
                } else {
                    ResponseEntity.accepted().body(body)
                }
            }
            is BootOutcome.Conflict -> {
                if (outcome.reason == RunnerUnavailableReason.WORKSPACE_NOT_FOUND) {
                    return ResponseEntity.notFound().build()
                }
                val workspace = getQuery.getSummary(WorkspaceId(id)) ?: return ResponseEntity.notFound().build()
                val snapshot = lifecycleService.readinessSnapshot(workspace)
                val body = WorkspaceConnectResponse.of(snapshot)
                if (outcome.reason == RunnerUnavailableReason.BOOT_LEASE_HELD) {
                    ResponseEntity.accepted().body(body)
                } else {
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body)
                }
            }
        }
    }

    @PostMapping("/{id}/repositories")
    @ApiResponse(responseCode = "204", description = "No Content")
    fun attachRepository(
        @PathVariable id: UUID,
        @Valid @RequestBody req: AttachWorkspaceRepositoryRequest,
    ): ResponseEntity<Void> {
        commandBus.dispatch(
            AttachWorkspaceRepositoryCommand(
                workspaceId = WorkspaceId(id),
                repositoryId = RepositoryId(requireNotNull(req.repositoryId)),
            ),
        )
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}/repositories/{repoId}")
    fun detachRepository(
        @PathVariable id: UUID,
        @PathVariable repoId: UUID,
    ): ResponseEntity<Void> {
        commandBus.dispatch(
            DetachWorkspaceRepositoryCommand(
                workspaceId = WorkspaceId(id),
                repositoryId = RepositoryId(repoId),
            ),
        )
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    fun destroy(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        commandBus.dispatch(DestroyWorkspaceCommand(WorkspaceId(id)))
        return ResponseEntity.noContent().build()
    }

    private fun primaryRepositoryId(req: CreateWorkspaceRequest): UUID? {
        if (req.repositoryId != null &&
            req.primaryRepositoryId != null &&
            req.repositoryId != req.primaryRepositoryId
        ) {
            throw IllegalArgumentException("repositoryId and primaryRepositoryId must match when both are supplied")
        }
        return req.repositoryId ?: req.primaryRepositoryId ?: req.repositoryIds.orEmpty().firstOrNull()
    }

    private fun selectedRepositoryIds(
        req: CreateWorkspaceRequest,
        primaryRepositoryId: UUID?,
    ): List<UUID> {
        val repositoryIds = req.repositoryIds.orEmpty()
        if (repositoryIds.isEmpty()) return emptyList()
        return (listOfNotNull(primaryRepositoryId) + repositoryIds)
            .distinct()
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupDiffService
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationInput
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.AgentSetupRepository
import com.jorisjonkers.personalstack.agents.domain.port.SetupRestartEventRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AgentSetupCatalogEntryResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AgentSetupCatalogResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AgentSetupDetailResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AgentSetupDiffResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AgentSetupReferenceResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AgentSetupValidationResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.SessionSetupStateResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.SetupPreviewResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.SetupTargetOptionResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.SetupTargetOptionsResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.SetupTransitionHistoryResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.SetupTransitionResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.WorkspaceRunnerSetupResponse
import com.jorisjonkers.personalstack.common.web.ProblemDetail
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class AgentSetupController(
    private val setups: AgentSetupRepository,
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val events: SetupRestartEventRepository,
    private val diffService: AgentSetupDiffService,
    private val validation: AgentSetupValidationService,
) {
    @GetMapping("/agent-setups")
    @Operation(summary = "List selectable agent setup catalog entries")
    fun catalog(): AgentSetupCatalogResponse =
        AgentSetupCatalogResponse(setups = setups.findSelectable().map(AgentSetupCatalogEntryResponse::of))

    @GetMapping("/agent-setups/{setupId}/versions/{version}")
    @Operation(summary = "Get an agent setup catalog entry")
    fun detail(
        @PathVariable setupId: String,
        @PathVariable version: Long,
    ): ResponseEntity<AgentSetupDetailResponse> {
        val entry =
            catalogEntry(AgentSetupId(setupId), AgentSetupVersion(version))
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(AgentSetupDetailResponse.of(entry))
    }

    @GetMapping("/workspaces/{workspaceId}/setup")
    @Operation(summary = "Get workspace runner setup metadata")
    fun workspaceSetup(
        @PathVariable workspaceId: UUID,
    ): ResponseEntity<WorkspaceRunnerSetupResponse> =
        workspaces
            .findById(WorkspaceId(workspaceId))
            ?.let { ResponseEntity.ok(WorkspaceRunnerSetupResponse.of(it)) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/workspaces/{workspaceId}/sessions/{sessionId}/setup")
    @Operation(summary = "Get current, pending, and failed setup state for a session")
    fun sessionSetup(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<SessionSetupStateResponse> {
        val session =
            sessionInWorkspace(WorkspaceId(workspaceId), WorkspaceAgentSessionId(sessionId))
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(SessionSetupStateResponse.of(session, latestFailedSessionEvent(session.id)))
    }

    @GetMapping("/workspaces/{workspaceId}/sessions/{sessionId}/setup-options")
    @Operation(summary = "List setup targets for a session with validation preview")
    fun sessionSetupOptions(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<SetupTargetOptionsResponse> {
        val workspace = workspaces.findById(WorkspaceId(workspaceId)) ?: return ResponseEntity.notFound().build()
        val session =
            sessionInWorkspace(workspace.id, WorkspaceAgentSessionId(sessionId))
                ?: return ResponseEntity.notFound().build()
        val options =
            setups.findSelectable().map { entry ->
                SetupTargetOptionResponse(
                    setup = AgentSetupCatalogEntryResponse.of(entry),
                    validation =
                        AgentSetupValidationResponse.of(
                            validation.validate(
                                AgentSetupValidationInput(
                                    workspace = workspace,
                                    targetId = entry.definition.id,
                                    targetVersion = entry.definition.version,
                                    session = session,
                                ),
                            ),
                        ),
                )
            }
        return ResponseEntity.ok(
            SetupTargetOptionsResponse(
                current = AgentSetupReferenceResponse.of(session.currentSetupId, session.currentSetupVersion),
                pending =
                    session.pendingSetupId?.let { id ->
                        AgentSetupReferenceResponse.of(id, requireNotNull(session.pendingSetupVersion))
                    },
                options = options,
            ),
        )
    }

    @GetMapping("/workspaces/{workspaceId}/sessions/{sessionId}/setup-preview")
    @Operation(summary = "Preview setup diff and validation for a session restart target")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Preview"),
            ApiResponse(
                responseCode = "422",
                description = "Validation failed",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun setupPreview(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
        @RequestParam targetSetupId: String,
        @RequestParam targetSetupVersion: Long,
    ): ResponseEntity<SetupPreviewResponse> {
        val workspace = workspaces.findById(WorkspaceId(workspaceId)) ?: return ResponseEntity.notFound().build()
        val session =
            sessionInWorkspace(workspace.id, WorkspaceAgentSessionId(sessionId))
                ?: return ResponseEntity.notFound().build()
        val targetId = AgentSetupId(targetSetupId)
        val targetVersion = AgentSetupVersion(targetSetupVersion)
        val result =
            validation.validate(
                AgentSetupValidationInput(
                    workspace = workspace,
                    targetId = targetId,
                    targetVersion = targetVersion,
                    session = session,
                ),
            )
        val from = setups.findDefinition(session.currentSetupId, session.currentSetupVersion)
        val to = setups.findDefinition(targetId, targetVersion)
        return ResponseEntity.ok(
            SetupPreviewResponse(
                current = AgentSetupReferenceResponse.of(session.currentSetupId, session.currentSetupVersion),
                target = AgentSetupReferenceResponse.of(targetId, targetVersion),
                diff =
                    if (from != null && to != null) {
                        AgentSetupDiffResponse.of(diffService.diff(from, to))
                    } else {
                        null
                    },
                validation = AgentSetupValidationResponse.of(result),
            ),
        )
    }

    @GetMapping("/workspaces/{workspaceId}/setup-transitions")
    @Operation(summary = "List setup transition history for a workspace")
    fun workspaceTransitionHistory(
        @PathVariable workspaceId: UUID,
    ): SetupTransitionHistoryResponse =
        SetupTransitionHistoryResponse(
            events = events.findAllByWorkspaceId(WorkspaceId(workspaceId)).map(SetupTransitionResponse::of),
        )

    @GetMapping("/workspaces/{workspaceId}/sessions/{sessionId}/setup-transitions")
    @Operation(summary = "List setup transition history for a session")
    fun sessionTransitionHistory(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<SetupTransitionHistoryResponse> {
        val session =
            sessionInWorkspace(WorkspaceId(workspaceId), WorkspaceAgentSessionId(sessionId))
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            SetupTransitionHistoryResponse(
                events = events.findAllBySessionId(session.id).map(SetupTransitionResponse::of),
            ),
        )
    }

    private fun catalogEntry(
        id: AgentSetupId,
        version: AgentSetupVersion,
    ): AgentSetupCatalogEntry? {
        val definition = setups.findDefinition(id, version) ?: return null
        val availability = setups.findAvailability(id, version) ?: return null
        return AgentSetupCatalogEntry(definition, availability)
    }

    private fun sessionInWorkspace(
        workspaceId: WorkspaceId,
        sessionId: WorkspaceAgentSessionId,
    ): WorkspaceAgentSession? =
        sessions
            .findById(sessionId)
            ?.takeIf { it.workspaceId == workspaceId }

    private fun latestFailedSessionEvent(sessionId: WorkspaceAgentSessionId): SetupRestartEvent? =
        events
            .findAllBySessionId(sessionId)
            .filter { it.status == SetupRestartEventStatus.FAILED }
            .maxByOrNull { it.updatedAt }
}

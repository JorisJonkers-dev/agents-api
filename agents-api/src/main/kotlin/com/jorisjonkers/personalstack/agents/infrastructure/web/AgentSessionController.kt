package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.command.SendUserInputCommand
import com.jorisjonkers.personalstack.agents.application.command.StartAgentSessionCommand
import com.jorisjonkers.personalstack.agents.application.command.StopAgentSessionCommand
import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.query.GetTurnHistoryQueryService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RestartAgentSessionInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RestartAgentSessionService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.RestartAgentSessionHttpRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.RestartAgentSessionResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.SendUserInputRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.StageInputRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.StagedInputResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.StartAgentSessionRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.TurnResponse
import com.jorisjonkers.personalstack.common.command.CommandBus
import com.jorisjonkers.personalstack.common.web.ProblemDetail
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
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

/**
 * `workspaceId` is bound from the path purely so Spring's routing
 * keeps the nested URL shape intact (sessions are conceptually
 * children of a workspace). The handler bodies operate on the
 * session id only — session uniqueness is guaranteed at the
 * persistence layer regardless of the parent workspace.
 */
@Suppress("UnusedParameter")
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/sessions")
class AgentSessionController(
    private val commandBus: CommandBus,
    private val turnHistory: GetTurnHistoryQueryService,
    private val sessions: WorkspaceAgentSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val gateway: AgentGatewayClient,
    private val restartAgentSession: RestartAgentSessionService,
) {
    @PostMapping
    fun start(
        @PathVariable workspaceId: UUID,
        @RequestBody req: StartAgentSessionRequest,
    ): ResponseEntity<Map<String, UUID>> {
        val sessionId = WorkspaceAgentSessionId.random()
        commandBus.dispatch(
            StartAgentSessionCommand(
                sessionId = sessionId,
                workspaceId = WorkspaceId(workspaceId),
                kind = req.kind,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("sessionId" to sessionId.value))
    }

    @PostMapping("/{sessionId}/input")
    fun send(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
        @RequestBody req: SendUserInputRequest,
    ): ResponseEntity<Void> {
        commandBus.dispatch(
            SendUserInputCommand(
                sessionId = WorkspaceAgentSessionId(sessionId),
                text = req.text,
                enter = req.enter,
            ),
        )
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/{sessionId}/restart")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "202",
                description = "Accepted",
                content = [Content(schema = Schema(implementation = RestartAgentSessionResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "Conflict",
                content = [Content(schema = Schema(implementation = RestartAgentSessionResponse::class))],
            ),
            ApiResponse(
                responseCode = "422",
                description = "Invalid setup request",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "503",
                description = "Service Unavailable",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    @Suppress("LongMethod")
    fun restart(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
        @RequestBody(required = false) req: RestartAgentSessionHttpRequest?,
    ): ResponseEntity<RestartAgentSessionResponse> {
        restartPreconditionConflict(workspaceId, sessionId, req)?.let { return it }
        val result =
            restartAgentSession.restart(
                RestartAgentSessionInput(
                    workspaceId = WorkspaceId(workspaceId),
                    sessionId = WorkspaceAgentSessionId(sessionId),
                    expectedGeneration = req?.expectedGeneration,
                    targetSetupId = req?.targetSetupId?.let(::AgentSetupId),
                    targetSetupVersion = req?.targetSetupVersion?.let(::AgentSetupVersion),
                ),
            )
        return when (result) {
            is RunnerSessionBindingResult.Bound ->
                ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(RestartAgentSessionResponse.of(result.session))

            is RunnerSessionBindingResult.Conflict ->
                result.current
                    ?.let { ResponseEntity.status(HttpStatus.CONFLICT).body(RestartAgentSessionResponse.of(it)) }
                    ?: ResponseEntity.status(HttpStatus.CONFLICT).build<RestartAgentSessionResponse>()

            is RunnerSessionBindingResult.Unavailable ->
                throw AgentRunnerUnavailableException(
                    workspaceId = result.workspaceId,
                    runnerStatus = result.runnerStatus,
                    retryAfterSeconds =
                        result.retryAfterSeconds
                            ?: AgentRunnerUnavailableException.DEFAULT_RETRY_AFTER_SECONDS,
                )
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
    private fun restartPreconditionConflict(
        workspaceId: UUID,
        sessionId: UUID,
        req: RestartAgentSessionHttpRequest?,
    ): ResponseEntity<RestartAgentSessionResponse>? {
        if (
            req?.expectedEpoch == null &&
            req?.expectedSetupId == null &&
            req?.expectedSetupVersion == null &&
            req?.expectedCurrentSetupId == null &&
            req?.expectedCurrentSetupVersion == null
        ) {
            return null
        }
        val expectedSetupId = req.expectedCurrentSetupId ?: req.expectedSetupId
        val expectedSetupVersion = req.expectedCurrentSetupVersion ?: req.expectedSetupVersion
        require((expectedSetupId == null) == (expectedSetupVersion == null)) {
            "expected setup id and version must be supplied together"
        }
        val workspaceModelId = WorkspaceId(workspaceId)
        val session = sessions.findById(WorkspaceAgentSessionId(sessionId)) ?: return null
        require(session.workspaceId == workspaceModelId) { "session does not belong to workspace: $sessionId" }
        if (req.expectedEpoch != null && req.expectedEpoch != session.epoch) return restartConflict(session)
        expectedSetupId ?: return null
        if (
            expectedSetupId != session.currentSetupId.value ||
            requireNotNull(expectedSetupVersion) != session.currentSetupVersion.value
        ) {
            return restartConflict(session)
        }
        return null
    }

    private fun restartConflict(session: WorkspaceAgentSession): ResponseEntity<RestartAgentSessionResponse> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(RestartAgentSessionResponse.of(session))

    @PostMapping("/{sessionId}/staged-inputs")
    fun stageInput(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
        @RequestBody req: StageInputRequest,
    ): ResponseEntity<StagedInputResponse> {
        val workspaceModelId = WorkspaceId(workspaceId)
        val session =
            sessions.findById(WorkspaceAgentSessionId(sessionId))
                ?: error("session not found: $sessionId")
        require(session.workspaceId == workspaceModelId) { "session does not belong to workspace: $sessionId" }
        val workspace = workspaces.findById(workspaceModelId) ?: error("workspace not found: $workspaceId")
        val gatewayAgentId =
            session.gatewayAgentId
                ?: throw AgentRunnerUnavailableException(
                    workspaceId = workspaceModelId,
                    runnerStatus = RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label,
                )
        val staged = gateway.stageInput(workspace, gatewayAgentId, req.content, req.name)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(StagedInputResponse(path = staged.path, bytes = staged.bytes, name = staged.name))
    }

    @GetMapping("/{sessionId}/turns")
    fun turns(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
    ): List<TurnResponse> = turnHistory.history(WorkspaceAgentSessionId(sessionId)).map(TurnResponse::of)

    @DeleteMapping("/{sessionId}")
    fun stop(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<Void> {
        commandBus.dispatch(StopAgentSessionCommand(WorkspaceAgentSessionId(sessionId)))
        return ResponseEntity.noContent().build()
    }
}

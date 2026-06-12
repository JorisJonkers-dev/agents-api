package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.command.SendUserInputCommand
import com.jorisjonkers.personalstack.agents.application.command.StartAgentSessionCommand
import com.jorisjonkers.personalstack.agents.application.command.StopAgentSessionCommand
import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.query.GetTurnHistoryQueryService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RestartAgentSessionInput
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RestartAgentSessionService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RunnerSessionBindingResult
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
    fun restart(
        @PathVariable workspaceId: UUID,
        @PathVariable sessionId: UUID,
        @RequestBody(required = false) req: RestartAgentSessionHttpRequest?,
    ): ResponseEntity<RestartAgentSessionResponse> {
        val result =
            restartAgentSession.restart(
                RestartAgentSessionInput(
                    workspaceId = WorkspaceId(workspaceId),
                    sessionId = WorkspaceAgentSessionId(sessionId),
                    expectedGeneration = req?.expectedGeneration,
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
        val gatewayAgentId = session.gatewayAgentId ?: error("session not bound to a gateway agent yet")
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

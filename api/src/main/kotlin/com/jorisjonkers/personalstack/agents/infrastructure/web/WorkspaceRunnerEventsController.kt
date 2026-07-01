package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.query.GetWorkspaceQueryService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.events.WorkspaceRunnerEventsBroadcaster
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@Hidden
@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspaceRunnerEventsController(
    private val broadcaster: WorkspaceRunnerEventsBroadcaster,
    private val getQuery: GetWorkspaceQueryService,
) {
    @GetMapping("/{id}/runner-events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<SseEmitter> {
        val workspaceId = WorkspaceId(id)
        val workspace = getQuery.getSummary(workspaceId) ?: return ResponseEntity.notFound().build()
        if (workspace.ownerUserId != null && workspace.ownerUserId != userId) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(broadcaster.subscribe(workspaceId))
    }
}

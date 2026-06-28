package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusBroadcaster
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Hidden
@RestController
@RequestMapping("/api/v1/sessions")
class SessionStatusController(
    private val broadcaster: SessionStatusBroadcaster,
) {
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<SseEmitter> =
        ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(broadcaster.subscribe(userId))
}

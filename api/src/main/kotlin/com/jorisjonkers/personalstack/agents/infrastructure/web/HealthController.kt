package com.jorisjonkers.personalstack.agents.infrastructure.web

import jakarta.annotation.security.PermitAll
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class HealthController {
    @GetMapping("/health")
    @PermitAll
    fun health(): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok(
            mapOf(
                "status" to "ok",
                "service" to "agents-api",
            ),
        )
}

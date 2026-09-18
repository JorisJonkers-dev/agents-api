package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.domain.port.AgentLoginStore
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AgentLoginStatusResponse
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Reports which providers have an Agent Login on the home volume.
 *
 * This replaces `/api/v1/credentials/status`, which reported what agents-api
 * had *captured and stored* for a user. Under ADR 0002 it stores nothing: the
 * CLI writes its own login when the user signs in from a terminal in an Agent
 * Session, and this only says whether that has happened yet.
 *
 * No `X-User-Id`. The old endpoint keyed on the forward-auth identity because
 * credentials were per-user rows; an Agent Login is a property of the home
 * volume, and there is one of those per container. Taking a user header would
 * imply a per-user answer this cannot give.
 *
 * A missing Agent Login is not an error and does not block a session: the CLI
 * prompts for sign-in itself, and this is what lets agents-ui say so first.
 *
 * SCOPE, and agents-ui must respect it: this answers for Agent Sessions that
 * run in *this container*, which today means a Scratch Workspace. A Repo-backed
 * Workspace still runs its sessions in a runner Pod, which has no access to
 * this volume and, since #64, no injected credential either -- so `present =
 * true` here says nothing about one. agents-ui must show the hint for every
 * Repo-backed Workspace regardless of what this reports, until #67 moves that
 * path in-container and the two agree.
 */
@RestController
@RequestMapping("/api/v1/agent-logins")
class AgentLoginController(
    private val logins: AgentLoginStore,
) {
    @GetMapping
    @Operation(summary = "Report which providers have an Agent Login on the home volume")
    fun status(): ResponseEntity<AgentLoginStatusResponse> =
        ResponseEntity.ok(AgentLoginStatusResponse.of(logins.statuses()))
}

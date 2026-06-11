package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.maintenance.RunnerMaintenanceService
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Hidden
@RestController
@RequestMapping("/api/v1/admin/runners")
class AdminRunnerController(
    private val maintenance: RunnerMaintenanceService,
) {
    data class RollingRestartResponse(
        val cycled: Int,
        val workspaceIds: List<String>,
    )

    /**
     * Gracefully scale down every active runner Pod so each workspace
     * transitions to IDLE with its PVC preserved. The next session start
     * on any workspace re-provisions the Pod pulling `:latest` and starts
     * a fresh agent process.
     *
     * Intended for two scenarios:
     *  1. After pushing a new agent-runner image to pick up the update.
     *  2. Before draining the runner node for maintenance.
     *
     * Requires the `X-User-Id` header (forwarded by the SSO proxy).
     */
    @PostMapping("/rolling-restart")
    fun rollingRestart(): RollingRestartResponse {
        val result = maintenance.gracefulScaleDownAll()
        return RollingRestartResponse(
            cycled = result.cycled,
            workspaceIds = result.workspaceIds,
        )
    }
}

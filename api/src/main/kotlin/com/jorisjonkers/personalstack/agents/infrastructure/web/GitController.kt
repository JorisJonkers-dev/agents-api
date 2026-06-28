package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.command.OpenPullRequestCommand
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.common.command.CommandBus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/git")
class GitController(
    private val commandBus: CommandBus,
) {
    data class OpenPrRequest(
        val repoDir: String,
        val title: String,
        val body: String,
        val base: String = "main",
    )

    @PostMapping("/open-pr")
    fun openPr(
        @PathVariable workspaceId: UUID,
        @RequestBody req: OpenPrRequest,
    ): ResponseEntity<Void> {
        commandBus.dispatch(
            OpenPullRequestCommand(
                workspaceId = WorkspaceId(workspaceId),
                repoDir = req.repoDir,
                title = req.title,
                body = req.body,
                base = req.base,
            ),
        )
        return ResponseEntity.accepted().build()
    }
}

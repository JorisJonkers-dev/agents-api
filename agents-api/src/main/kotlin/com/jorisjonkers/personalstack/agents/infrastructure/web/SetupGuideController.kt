package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.setup.SetupGuideService
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/projects/{projectId}/links/{linkId}")
class SetupGuideController(
    private val links: GithubLinkRepository,
    private val guides: SetupGuideService,
) {
    @GetMapping("/setup-guide", produces = ["text/markdown", MediaType.TEXT_PLAIN_VALUE])
    @Suppress("UnusedParameter") // projectId carried in the URL only
    fun guide(
        @PathVariable projectId: UUID,
        @PathVariable linkId: UUID,
    ): ResponseEntity<String> {
        val link = links.findById(GithubLinkId(linkId)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(guides.render(link))
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.RepositoryInstallationStatusService
import com.jorisjonkers.personalstack.agents.application.RepositoryVerificationService
import com.jorisjonkers.personalstack.agents.application.command.CreateRepositoryCommand
import com.jorisjonkers.personalstack.agents.application.command.DeleteRepositoryCommand
import com.jorisjonkers.personalstack.agents.application.query.RepositoryQueryService
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.CreateRepositoryRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.ProjectResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.RepositoryInstallationStatusResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.RepositoryResponse
import com.jorisjonkers.personalstack.common.command.CommandBus
import jakarta.validation.Valid
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

@RestController
@RequestMapping("/api/v1/repositories")
class RepositoryController(
    private val commandBus: CommandBus,
    private val repositoryQuery: RepositoryQueryService,
    private val verificationService: RepositoryVerificationService,
    private val installationStatusService: RepositoryInstallationStatusService,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody req: CreateRepositoryRequest,
    ): ResponseEntity<RepositoryResponse> {
        val id = RepositoryId.random()
        commandBus.dispatch(
            CreateRepositoryCommand(
                repositoryId = id,
                name = req.name,
                repoUrl = req.repoUrl,
                defaultBranch = req.defaultBranch,
            ),
        )
        val detail =
            repositoryQuery.get(id)
                ?: error("repository not visible immediately after create")
        return ResponseEntity.status(HttpStatus.CREATED).body(RepositoryResponse.of(detail.repository))
    }

    @GetMapping
    fun list(): List<RepositoryResponse> = repositoryQuery.list().map(RepositoryResponse::of)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val detail = repositoryQuery.get(RepositoryId(id)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            mapOf(
                "repository" to RepositoryResponse.of(detail.repository),
                "attachedProjects" to detail.attachedProjects.map(ProjectResponse::of),
            ),
        )
    }

    @PostMapping("/{id}/verify")
    fun verify(
        @PathVariable id: UUID,
    ): ResponseEntity<RepositoryResponse> {
        val updated = verificationService.reverify(RepositoryId(id)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(RepositoryResponse.of(updated))
    }

    /**
     * Live GitHub App install-status for the repo. Backs both the initial
     * load and the manual "re-check" — it queries GitHub fresh and stores
     * nothing, and never mints a token.
     */
    @GetMapping("/{id}/installation-status")
    fun installationStatus(
        @PathVariable id: UUID,
    ): ResponseEntity<RepositoryInstallationStatusResponse> {
        val status = installationStatusService.status(RepositoryId(id)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(RepositoryInstallationStatusResponse.of(status))
    }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        commandBus.dispatch(DeleteRepositoryCommand(RepositoryId(id)))
        return ResponseEntity.noContent().build()
    }
}

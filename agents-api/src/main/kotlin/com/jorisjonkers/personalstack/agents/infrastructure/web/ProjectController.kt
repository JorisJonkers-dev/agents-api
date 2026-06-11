package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.command.AddGithubLinkCommand
import com.jorisjonkers.personalstack.agents.application.command.AttachDeployKeyCommand
import com.jorisjonkers.personalstack.agents.application.command.CreateProjectCommand
import com.jorisjonkers.personalstack.agents.application.command.LinkRepositoryToProjectCommand
import com.jorisjonkers.personalstack.agents.application.command.RemoveGithubLinkCommand
import com.jorisjonkers.personalstack.agents.application.command.UnlinkRepositoryFromProjectCommand
import com.jorisjonkers.personalstack.agents.application.query.ProjectQueryService
import com.jorisjonkers.personalstack.agents.application.query.RepositoryQueryService
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AddGithubLinkRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.AttachDeployKeyRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.CreateProjectRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.GithubLinkResponse
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.LinkRepositoryRequest
import com.jorisjonkers.personalstack.agents.infrastructure.web.dto.ProjectResponse
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
@RequestMapping("/api/v1/projects")
class ProjectController(
    private val commandBus: CommandBus,
    private val projectQuery: ProjectQueryService,
    private val repositoryQuery: RepositoryQueryService,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody req: CreateProjectRequest,
    ): ResponseEntity<ProjectResponse> {
        val id = ProjectId.random()
        commandBus.dispatch(
            CreateProjectCommand(
                projectId = id,
                name = req.name,
                slug = req.slug,
                description = req.description ?: "",
            ),
        )
        val view = projectQuery.get(id) ?: error("project not visible immediately after create")
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.of(view.project))
    }

    @GetMapping
    fun list(): List<ProjectResponse> = projectQuery.list().map(ProjectResponse::of)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val view = projectQuery.get(ProjectId(id)) ?: return ResponseEntity.notFound().build()
        val repos = repositoryQuery.listByProject(ProjectId(id))
        return ResponseEntity.ok(
            mapOf(
                "project" to ProjectResponse.of(view.project),
                "links" to view.links.map(GithubLinkResponse::of),
                "repositories" to repos.map(RepositoryResponse::of),
            ),
        )
    }

    @PostMapping("/{id}/repositories")
    fun linkRepository(
        @PathVariable id: UUID,
        @Valid @RequestBody req: LinkRepositoryRequest,
    ): ResponseEntity<List<RepositoryResponse>> {
        commandBus.dispatch(
            LinkRepositoryToProjectCommand(
                projectId = ProjectId(id),
                repositoryId = RepositoryId(req.repositoryId),
            ),
        )
        val repos = repositoryQuery.listByProject(ProjectId(id))
        return ResponseEntity.status(HttpStatus.CREATED).body(repos.map(RepositoryResponse::of))
    }

    @DeleteMapping("/{id}/repositories/{repoId}")
    fun unlinkRepository(
        @PathVariable id: UUID,
        @PathVariable repoId: UUID,
    ): ResponseEntity<Void> {
        commandBus.dispatch(
            UnlinkRepositoryFromProjectCommand(
                projectId = ProjectId(id),
                repositoryId = RepositoryId(repoId),
            ),
        )
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/links")
    @Deprecated(
        "Use POST /api/v1/repositories and POST /api/v1/projects/{id}/repositories. " +
            "Kept until PR F migrates the agents-ui.",
    )
    fun addLink(
        @PathVariable id: UUID,
        @Valid @RequestBody req: AddGithubLinkRequest,
    ): ResponseEntity<GithubLinkResponse> {
        val linkId = GithubLinkId.random()
        commandBus.dispatch(
            AddGithubLinkCommand(
                linkId = linkId,
                projectId = ProjectId(id),
                name = req.name,
                repoUrl = req.repoUrl,
                defaultBranch = req.defaultBranch,
            ),
        )
        val link =
            projectQuery
                .get(ProjectId(id))
                ?.links
                ?.firstOrNull { it.id == linkId }
                ?: error("link not visible after add")
        return ResponseEntity.status(HttpStatus.CREATED).body(GithubLinkResponse.of(link))
    }

    @PostMapping("/{projectId}/links/{linkId}/key")
    @Suppress("UnusedParameter") // projectId carried in the URL only
    fun attachKey(
        @PathVariable projectId: UUID,
        @PathVariable linkId: UUID,
        @Valid @RequestBody req: AttachDeployKeyRequest,
    ): ResponseEntity<Void> {
        commandBus.dispatch(
            AttachDeployKeyCommand(
                linkId = GithubLinkId(linkId),
                privateKeyOpenssh = req.privateKeyOpenssh,
                publicKeyOpenssh = req.publicKeyOpenssh,
                knownHosts = req.knownHosts,
            ),
        )
        return ResponseEntity.accepted().build()
    }

    @DeleteMapping("/{projectId}/links/{linkId}")
    @Suppress("UnusedParameter") // projectId carried in the URL only
    fun removeLink(
        @PathVariable projectId: UUID,
        @PathVariable linkId: UUID,
    ): ResponseEntity<Void> {
        commandBus.dispatch(RemoveGithubLinkCommand(GithubLinkId(linkId)))
        return ResponseEntity.noContent().build()
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.Project
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateProjectRequest(
    @field:NotBlank @field:Size(min = 1, max = 80) val name: String,
    @field:Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") val slug: String,
    @field:Size(max = 1_000) val description: String? = null,
)

data class ProjectResponse(
    val id: UUID,
    val name: String,
    val slug: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(p: Project) =
            ProjectResponse(
                id = p.id.value,
                name = p.name,
                slug = p.slug,
                description = p.description,
                createdAt = p.createdAt,
                updatedAt = p.updatedAt,
            )
    }
}

data class AddGithubLinkRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val repoUrl: String,
    val defaultBranch: String = "main",
)

data class GithubLinkResponse(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val repoUrl: String,
    val defaultBranch: String,
    val vaultKeyPath: String,
    val deployKeyFingerprint: String?,
    val deployKeyAddedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(l: GithubLink) =
            GithubLinkResponse(
                id = l.id.value,
                projectId = l.projectId.value,
                name = l.name,
                repoUrl = l.repoUrl,
                defaultBranch = l.defaultBranch,
                vaultKeyPath = l.vaultKeyPath,
                deployKeyFingerprint = l.deployKeyFingerprint,
                deployKeyAddedAt = l.deployKeyAddedAt,
                createdAt = l.createdAt,
                updatedAt = l.updatedAt,
            )
    }
}

data class AttachDeployKeyRequest(
    @field:NotBlank val privateKeyOpenssh: String,
    @field:NotBlank val publicKeyOpenssh: String,
    val knownHosts: String? = null,
)

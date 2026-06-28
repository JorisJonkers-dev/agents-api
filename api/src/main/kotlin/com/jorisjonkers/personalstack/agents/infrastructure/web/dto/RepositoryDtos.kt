package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.application.RepositoryInstallationStatusService
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateRepositoryRequest(
    @field:NotBlank @field:Size(min = 1, max = 80) val name: String,
    @field:NotBlank val repoUrl: String,
    val defaultBranch: String = "main",
)

data class AccessVerificationResponse(
    val defaultBranchProtected: Boolean?,
    val checkedAt: Instant?,
    val messages: List<String>,
) {
    companion object {
        fun of(v: com.jorisjonkers.personalstack.agents.domain.model.AccessVerification) =
            AccessVerificationResponse(
                defaultBranchProtected = v.defaultBranchProtected,
                checkedAt = v.checkedAt,
                messages = v.messages,
            )
    }
}

data class RepositoryResponse(
    val id: UUID,
    val name: String,
    val repoUrl: String,
    val defaultBranch: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val verification: AccessVerificationResponse?,
) {
    companion object {
        fun of(r: Repository) =
            RepositoryResponse(
                id = r.id.value,
                name = r.name,
                repoUrl = r.repoUrl,
                defaultBranch = r.defaultBranch,
                createdAt = r.createdAt,
                updatedAt = r.updatedAt,
                verification = r.verification?.let(AccessVerificationResponse::of),
            )
    }
}

data class LinkRepositoryRequest(
    val repositoryId: UUID,
)

/**
 * Live GitHub App installation status for a repository. `state` is one
 * of INSTALLED / NOT_INSTALLED / UNKNOWN; the UI builds the install link
 * from the repository's URL. No token is ever returned.
 */
data class RepositoryInstallationStatusResponse(
    val state: String,
    val checkedAt: Instant,
    val detail: String?,
) {
    companion object {
        fun of(s: RepositoryInstallationStatusService.Status) =
            RepositoryInstallationStatusResponse(
                state = s.state.name,
                checkedAt = s.checkedAt,
                detail = s.detail,
            )
    }
}

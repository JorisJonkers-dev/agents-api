package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

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
    val read: Boolean?,
    val write: Boolean?,
    val defaultBranchProtected: Boolean?,
    val checkedAt: Instant?,
    val messages: List<String>,
) {
    companion object {
        fun of(v: com.jorisjonkers.personalstack.agents.domain.model.AccessVerification) =
            AccessVerificationResponse(
                read = v.read,
                write = v.write,
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
    val vaultKeyPath: String,
    val deployKeyFingerprint: String?,
    val deployKeyAddedAt: Instant?,
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
                vaultKeyPath = r.vaultKeyPath,
                deployKeyFingerprint = r.deployKeyFingerprint,
                deployKeyAddedAt = r.deployKeyAddedAt,
                createdAt = r.createdAt,
                updatedAt = r.updatedAt,
                verification = r.verification?.let(AccessVerificationResponse::of),
            )
    }
}

data class AttachRepositoryDeployKeyRequest(
    @field:NotBlank val privateKeyOpenssh: String,
    @field:NotBlank val publicKeyOpenssh: String,
    val knownHosts: String? = null,
)

data class LinkRepositoryRequest(
    val repositoryId: UUID,
)

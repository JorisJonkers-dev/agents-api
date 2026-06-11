package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Creates the GithubLink row without yet attaching a deploy key —
 * the link is in "needs key" state until the operator follows the
 * setup guide and POSTs the keypair via AttachDeployKey. The Vault
 * path is pre-allocated so the setup guide can deep-link to it
 * (and so a partial flow doesn't ship a link without a planned
 * home for its key).
 */
@Component
class AddGithubLinkCommandHandler(
    private val projects: ProjectsRepository,
    private val links: GithubLinkRepository,
) : CommandHandler<AddGithubLinkCommand> {
    @Transactional
    override fun handle(command: AddGithubLinkCommand) {
        val project =
            projects.findById(command.projectId)
                ?: error("project not found: ${command.projectId}")
        require(command.name.isNotBlank()) { "link name must not be blank" }
        require(command.repoUrl.isNotBlank()) { "repo URL must not be blank" }
        val now = Instant.now()
        val vaultPath = "secret/data/agents/projects/${project.id}/repos/${command.linkId}"
        links.save(
            GithubLink(
                id = command.linkId,
                projectId = project.id,
                name = command.name.trim(),
                repoUrl = command.repoUrl.trim(),
                defaultBranch = command.defaultBranch.ifBlank { "main" },
                vaultKeyPath = vaultPath,
                deployKeyFingerprint = null,
                deployKeyAddedAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.VerifyRepositoryAccess
import com.jorisjonkers.personalstack.agents.application.exception.RepositoryAccessDeniedException
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupSelectionService
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Creates the workspace record and provisions the runner Pod. For a
 * repo-backed workspace the clone happens inside the runner at boot
 * (the orchestrator passes REPO_URL/REPO_BRANCH and the entrypoint
 * clones into /workspace/<repo-name> with the mounted deploy key) — not from here.
 * An earlier create-time `gateway.clone` raced the runner's startup:
 * it fired before the gateway was up, failed, and was swallowed,
 * leaving the workspace empty.
 *
 * Repo resolution prefers [CreateWorkspaceCommand.repositoryId]
 * (the new shape). If only the legacy [CreateWorkspaceCommand.githubLinkId]
 * is set, the handler still resolves through [GithubLinkRepository]
 * and logs a deprecation warning so the migration is visible in
 * production logs.
 */
@Component
@Suppress("DEPRECATION", "LongParameterList")
class CreateWorkspaceCommandHandler(
    private val workspaces: WorkspaceRepository,
    private val orchestrator: AgentRunnerOrchestrator,
    private val projectRepositories: ProjectRepositoryRepository,
    private val workspaceRepositories: WorkspaceRepositoryRepository,
    /**
     * Optional — present only when the Projects feature is wired
     * (Vault enabled). When absent, every CreateWorkspace must
     * either supply repoUrl or stay repo-less.
     */
    private val githubLinks: ObjectProvider<GithubLinkRepository>,
    /**
     * The new per-Repository lookup. Same wiring rule as
     * [githubLinks] — `@ConditionalOnProperty` keeps it absent in
     * tests that disable Vault.
     */
    private val repositories: ObjectProvider<RepositoryRepository>,
    private val verifyAccess: VerifyRepositoryAccess,
    private val setupSelection: AgentSetupSelectionService,
) : CommandHandler<CreateWorkspaceCommand> {
    private val log = LoggerFactory.getLogger(CreateWorkspaceCommandHandler::class.java)

    @Transactional
    override fun handle(command: CreateWorkspaceCommand) {
        require(command.kind != WorkspaceKind.CHAT) {
            "CHAT workspaces are not persisted — use StartChatSessionCommand instead"
        }
        if (command.repositoryId != null && command.githubLinkId != null) {
            log.warn(
                "CreateWorkspaceCommand received both repositoryId={} and (deprecated) githubLinkId={}; " +
                    "preferring repositoryId",
                command.repositoryId,
                command.githubLinkId,
            )
        } else if (command.githubLinkId != null) {
            log.warn(
                "CreateWorkspaceCommand uses deprecated githubLinkId={}; migrate the caller to repositoryId",
                command.githubLinkId,
            )
        }
        val resolved = resolveRepo(command)
        if (command.kind == WorkspaceKind.REPO_BACKED && resolved.repoUrl != null) {
            verifyOrFail(resolved.repoUrl, resolved.branch, resolved.repositoryId)
        }
        val setup = setupSelection.defaultSelectable()
        val workspace = persistInitial(command, resolved, setup)
        seedRepositoryMembership(workspace, command)
        val withPod = provisionAndUpdate(workspace, RunnerSetupProvisioningSpec.from(setup.definition))
        log.info("workspace {} provisioned as pod {}", workspace.id, withPod.podName)
    }

    private fun verifyOrFail(
        repoUrl: String,
        branch: String?,
        repositoryId: RepositoryId?,
    ) {
        val effectiveBranch = branch?.takeIf { it.isNotBlank() } ?: "main"
        val result = verifyAccess.verify(repoUrl, effectiveBranch)
        // read == false is an explicit "auth ok but no read" or an
        // unauthenticated reject — either way the runner could never
        // clone, so fail loudly instead of leaving a dead workspace.
        // read == null (gateway unavailable) is NOT fatal: it degrades
        // to a warning so a verify-gateway outage can't block the
        // whole create flow.
        if (result.read == false) {
            throw RepositoryAccessDeniedException(
                repositoryId = repositoryId ?: RepositoryId.random(),
                reason = result.messages.joinToString("; ").ifBlank { "read access denied" },
            )
        }
        // write == false / unprotected main / inconclusive checks are
        // loud warnings only — the operator sets branch protection on
        // GitHub, and a read-only key is recorded so the UI can prompt.
        result.messages.forEach { log.warn("workspace create verify warning [{}]: {}", repoUrl, it) }
    }

    private fun persistInitial(
        command: CreateWorkspaceCommand,
        resolved: ResolvedRepo,
        setup: AgentSetupCatalogEntry,
    ): Workspace {
        val now = Instant.now()
        val workspace =
            Workspace(
                id = command.workspaceId,
                name = command.name,
                repoUrl = resolved.repoUrl,
                branch = resolved.branch,
                podName = null,
                pvcName = null,
                gatewayEndpoint = null,
                status = WorkspaceStatus.PENDING,
                createdAt = now,
                updatedAt = now,
                repositoryId = resolved.repositoryId,
                projectId = command.projectId,
                kind = command.kind,
                githubLinkId = resolved.legacyLinkId,
                currentRunnerSetupId = setup.definition.id,
                currentRunnerSetupVersion = setup.definition.version,
            )
        workspaces.save(workspace)
        return workspace
    }

    private fun seedRepositoryMembership(
        workspace: Workspace,
        command: CreateWorkspaceCommand,
    ) {
        val repoId = workspace.repositoryId ?: return
        val repoPort = repositories.ifAvailable ?: return
        val realPrimaryRepoId =
            repoPort
                .findById(repoId)
                ?.id
                ?: return

        workspaceRepositories.attach(workspace.id, realPrimaryRepoId, isPrimary = true)
        selectedExtraRepositoryIds(command, realPrimaryRepoId)
            .forEach { repositoryId ->
                val repository = requireRepository(repoPort, repositoryId)
                workspaceRepositories.attach(
                    workspace.id,
                    repository.id,
                    isPrimary = false,
                )
            }
    }

    private fun selectedExtraRepositoryIds(
        command: CreateWorkspaceCommand,
        primaryRepoId: RepositoryId,
    ): List<RepositoryId> {
        val extras =
            if (command.repositoryIds.isNotEmpty()) {
                command.repositoryIds.filterNot { it == primaryRepoId }
            } else {
                command.projectId
                    ?.let { projectRepositories.findAllByProjectId(it) }
                    .orEmpty()
                    .map { it.repositoryId }
                    .filterNot { it == primaryRepoId }
            }
        return extras.distinct()
    }

    private fun requireRepository(
        repos: RepositoryRepository,
        repositoryId: RepositoryId,
    ) = repos.findById(repositoryId)
        ?: throw NoSuchElementException("repository not found: repositoryId=${repositoryId.value}")

    private fun provisionAndUpdate(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
    ): Workspace {
        val handle = orchestrator.provision(workspace, setup, workspace.runnerSetupGeneration)
        val withPod =
            workspace.withPodInfo(
                podName = handle.podName,
                pvcName = handle.pvcName,
                gatewayEndpoint = handle.gatewayEndpoint,
            )
        workspaces.save(withPod)
        return withPod
    }

    private data class ResolvedRepo(
        val repoUrl: String?,
        val branch: String?,
        val repositoryId: RepositoryId?,
        val legacyLinkId: GithubLinkId?,
    )

    private fun resolveRepo(command: CreateWorkspaceCommand): ResolvedRepo {
        val primaryRepositoryId = primaryRepositoryId(command)
        return when {
            command.kind == WorkspaceKind.SCRATCH -> ResolvedRepo(null, null, null, null)
            primaryRepositoryId != null -> resolveFromRepository(command, primaryRepositoryId)
            command.githubLinkId != null -> resolveFromLegacyLink(command, command.githubLinkId)
            else -> ResolvedRepo(command.repoUrl, command.branch, null, null)
        }
    }

    private fun primaryRepositoryId(command: CreateWorkspaceCommand): RepositoryId? =
        command.repositoryId ?: command.repositoryIds.firstOrNull()

    private fun resolveFromRepository(
        command: CreateWorkspaceCommand,
        repoId: RepositoryId,
    ): ResolvedRepo {
        // Split the two failure modes apart so the API surfaces a
        // distinct status:
        //   * RepositoryRepository absent → IllegalStateException
        //     (config issue, 409 Conflict via GlobalExceptionHandler).
        //   * Row not found → NoSuchElementException (404).
        val repos =
            repositories.ifAvailable
                ?: throw IllegalStateException(
                    "Repository feature is not configured; cannot create workspace for repositoryId=${repoId.value}",
                )
        val repo =
            repos.findById(repoId)
                ?: throw NoSuchElementException(
                    "repository not found: repositoryId=${repoId.value}",
                )
        val branch = command.branch?.takeIf { it.isNotBlank() } ?: repo.defaultBranch
        // legacyLinkId is set ONLY when a real github_links row with
        // the same id exists. Setting it unconditionally used to be
        // safe under the assumption every repository carried a 1:1
        // github_links mirror, but post-V9 repositories created
        // directly (no project link first) don't — the
        // `workspaces.github_link_id → github_links.id` FK then
        // violates on insert with `DataIntegrityViolationException`
        // and the workspace create fails with an opaque 500.
        //
        // The orchestrator's `resolveKeyMaterial` accepts a null
        // linkId (falls back to the cluster-wide deploy key); this
        // is the right shape once the per-link Vault path is fully
        // retired anyway.
        val legacyLinkId = GithubLinkId(repoId.value)
        val realLegacyId =
            githubLinks
                .ifAvailable
                ?.findById(legacyLinkId)
                ?.let { legacyLinkId }
        return ResolvedRepo(
            repoUrl = repo.repoUrl,
            branch = branch,
            repositoryId = repoId,
            legacyLinkId = realLegacyId,
        )
    }

    private fun resolveFromLegacyLink(
        command: CreateWorkspaceCommand,
        linkId: GithubLinkId,
    ): ResolvedRepo {
        val links =
            githubLinks.ifAvailable
                ?: throw IllegalStateException(
                    "Projects feature is not configured; cannot create workspace for githubLinkId=${linkId.value}",
                )
        val link =
            links.findById(linkId)
                ?: throw NoSuchElementException(
                    "github link not found: githubLinkId=${linkId.value}",
                )
        val branch = command.branch?.takeIf { it.isNotBlank() } ?: link.defaultBranch
        return ResolvedRepo(
            repoUrl = link.repoUrl,
            branch = branch,
            repositoryId = RepositoryId(linkId.value),
            legacyLinkId = linkId,
        )
    }
}

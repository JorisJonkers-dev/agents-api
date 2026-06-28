package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.VerifyRepositoryAccess
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupSelectionService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService.BootOutcome
import com.jorisjonkers.personalstack.agents.application.workspacerunner.WorkspaceRunnerLifecycleService.BootProvisioningOutcome
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Suppress("DEPRECATION")
class CreateWorkspaceCommandHandlerTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val lifecycleService = mockk<WorkspaceRunnerLifecycleService>()
    private val projectRepositoryLinks = mockk<ProjectRepositoryRepository>(relaxed = true)
    private val workspaceRepositoryLinks = mockk<WorkspaceRepositoryRepository>(relaxed = true)
    private val githubLinks = mockk<GithubLinkRepository>()
    private val linkProvider =
        mockk<ObjectProvider<GithubLinkRepository>> {
            every { ifAvailable } returns githubLinks
        }
    private val repositories = mockk<RepositoryRepository>(relaxed = true)
    private val repositoryProvider =
        mockk<ObjectProvider<RepositoryRepository>> {
            every { ifAvailable } returns repositories
        }
    private val verifyAccess =
        mockk<VerifyRepositoryAccess> {
            // Default: read-write + protected so existing happy-path
            // tests don't have to opt in. Individual tests override.
            every { verify(any(), any()) } returns
                VerifyRepositoryAccess.Result(
                    defaultBranchProtected = true,
                    checkedAt = Instant.now(),
                    messages = emptyList(),
                )
        }
    private val setupSelection = mockk<AgentSetupSelectionService>()
    private val setup = setupEntry()
    private val tx =
        mockk<TransactionTemplate> {
            every { execute<Any?>(any()) } answers {
                @Suppress("UNCHECKED_CAST")
                (firstArg() as TransactionCallback<Any?>).doInTransaction(mockk(relaxed = true))
            }
        }
    private val handler =
        CreateWorkspaceCommandHandler(
            workspaces,
            lifecycleService,
            projectRepositoryLinks,
            workspaceRepositoryLinks,
            linkProvider,
            repositoryProvider,
            verifyAccess,
            setupSelection,
            tx,
        )

    init {
        every { setupSelection.defaultSelectable() } returns setup
        every { lifecycleService.boot(any(), any()) } returns
            BootOutcome.Ready(
                workspace = mockk(relaxed = true),
                provisioning = BootProvisioningOutcome.Provisioned("p", "v", "http://p.svc:8090"),
            )
    }

    @Test
    fun `handle persists workspace and triggers boot when repo is provided`() {
        val saved = slot<Workspace>()
        every { workspaces.save(capture(saved)) } answers { saved.captured }

        val id = WorkspaceId.random()
        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = id,
                name = "demo",
                repoUrl = "git@github.com:owner/repo.git",
                branch = null,
            ),
        )

        verify { lifecycleService.boot(any(), any()) }
        verify(exactly = 1) { workspaces.save(any()) }
        verify(exactly = 0) { workspaceRepositoryLinks.attach(any(), any(), any()) }
    }

    @Test
    fun `handle without repo still triggers boot`() {
        every { workspaces.save(any()) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "qa",
                repoUrl = null,
                branch = null,
            ),
        )

        verify { lifecycleService.boot(any(), any()) }
    }

    @Test
    fun `handle resolves repoUrl + branch from a GithubLink when linkId is set`() {
        val linkId = GithubLinkId.random()
        val link =
            GithubLink(
                id = linkId,
                projectId = ProjectId.random(),
                name = "agents",
                repoUrl = "git@github.com:JorisJonkers-dev/agents.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { githubLinks.findById(linkId) } returns link
        val saved = mutableListOf<Workspace>()
        every { workspaces.save(capture(saved)) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "demo",
                repoUrl = null,
                branch = null,
                githubLinkId = linkId,
            ),
        )

        assertThat(saved.first().repoUrl).isEqualTo("git@github.com:JorisJonkers-dev/agents.git")
        assertThat(saved.first().branch).isEqualTo("main")
        assertThat(saved.first().githubLinkId).isEqualTo(linkId)
    }

    @Test
    fun `handle resolves from repositoryId and mirrors the legacy linkId when a matching github_links row exists`() {
        val repoId = RepositoryId.random()
        val repository =
            Repository(
                id = repoId,
                name = "agents",
                repoUrl = "git@github.com:JorisJonkers-dev/agents.git",
                defaultBranch = "trunk",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { repositories.findById(repoId) } returns repository
        // Mirror exists in github_links — the legacy per-link Vault
        // path is still valid for this repo, so legacyLinkId carries
        // through.
        every { githubLinks.findById(GithubLinkId(repoId.value)) } returns
            GithubLink(
                id = GithubLinkId(repoId.value),
                projectId = ProjectId.random(),
                name = "agents",
                repoUrl = repository.repoUrl,
                defaultBranch = repository.defaultBranch,
                createdAt = repository.createdAt,
                updatedAt = repository.updatedAt,
            )
        val saved = mutableListOf<Workspace>()
        every { workspaces.save(capture(saved)) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "demo",
                repoUrl = null,
                branch = null,
                repositoryId = repoId,
            ),
        )

        assertThat(saved.first().repoUrl).isEqualTo("git@github.com:JorisJonkers-dev/agents.git")
        assertThat(saved.first().branch).isEqualTo("trunk")
        assertThat(saved.first().repositoryId).isEqualTo(repoId)
        assertThat(saved.first().githubLinkId?.value).isEqualTo(repoId.value)
    }

    @Test
    fun `repositoryId create persists then attaches primary before boot`() {
        val repoId = RepositoryId.random()
        val repository =
            Repository(
                id = repoId,
                name = "agents",
                repoUrl = "git@github.com:JorisJonkers-dev/agents.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { repositories.findById(repoId) } returns repository
        every { githubLinks.findById(GithubLinkId(repoId.value)) } returns null
        every { workspaces.save(any()) } answers { firstArg() }
        val workspaceId = WorkspaceId.random()

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = workspaceId,
                name = "demo",
                repoUrl = null,
                branch = null,
                repositoryId = repoId,
            ),
        )

        verifyOrder {
            workspaces.save(match { it.id == workspaceId && it.status == WorkspaceStatus.PENDING })
            workspaceRepositoryLinks.attach(workspaceId, repoId, isPrimary = true)
            lifecycleService.boot(workspaceId, WorkspaceAgentKind.CLAUDE)
        }
    }

    @Test
    fun `project repositoryId create attaches primary and project extras before boot`() {
        val projectId = ProjectId.random()
        val primaryRepoId = RepositoryId.random()
        val extraRepoId = RepositoryId.random()
        val secondExtraRepoId = RepositoryId.random()
        val repository =
            Repository(
                id = primaryRepoId,
                name = "agents",
                repoUrl = "git@github.com:JorisJonkers-dev/agents.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { repositories.findById(primaryRepoId) } returns repository
        every { repositories.findById(extraRepoId) } returns repository(extraRepoId, "extra-one")
        every { repositories.findById(secondExtraRepoId) } returns repository(secondExtraRepoId, "extra-two")
        every { githubLinks.findById(GithubLinkId(primaryRepoId.value)) } returns null
        every { projectRepositoryLinks.findAllByProjectId(projectId) } returns
            listOf(
                ProjectRepositoryRepository.Link(projectId, primaryRepoId, Instant.now()),
                ProjectRepositoryRepository.Link(projectId, extraRepoId, Instant.now()),
                ProjectRepositoryRepository.Link(projectId, secondExtraRepoId, Instant.now()),
            )
        every { workspaces.save(any()) } answers { firstArg() }
        val workspaceId = WorkspaceId.random()

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = workspaceId,
                name = "demo",
                repoUrl = null,
                branch = null,
                projectId = projectId,
                repositoryId = primaryRepoId,
            ),
        )

        verifyOrder {
            workspaces.save(match { it.id == workspaceId && it.status == WorkspaceStatus.PENDING })
            workspaceRepositoryLinks.attach(workspaceId, primaryRepoId, isPrimary = true)
            workspaceRepositoryLinks.attach(workspaceId, extraRepoId, isPrimary = false)
            workspaceRepositoryLinks.attach(workspaceId, secondExtraRepoId, isPrimary = false)
            lifecycleService.boot(workspaceId, WorkspaceAgentKind.CLAUDE)
        }
    }

    @Test
    fun `repositoryIds-only create treats first selected repository as primary and attaches distinct extras`() {
        val primaryRepoId = RepositoryId.random()
        val extraRepoId = RepositoryId.random()
        val secondExtraRepoId = RepositoryId.random()
        every { repositories.findById(primaryRepoId) } returns repository(primaryRepoId, "primary")
        every { repositories.findById(extraRepoId) } returns repository(extraRepoId, "extra")
        every { repositories.findById(secondExtraRepoId) } returns repository(secondExtraRepoId, "docs")
        every { githubLinks.findById(GithubLinkId(primaryRepoId.value)) } returns null
        val saved = mutableListOf<Workspace>()
        every { workspaces.save(capture(saved)) } answers { firstArg() }
        val workspaceId = WorkspaceId.random()

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = workspaceId,
                name = "demo",
                repoUrl = null,
                branch = null,
                repositoryIds = listOf(primaryRepoId, extraRepoId, primaryRepoId, secondExtraRepoId),
            ),
        )

        assertThat(saved.first().repositoryId).isEqualTo(primaryRepoId)
        verifyOrder {
            workspaces.save(match { it.id == workspaceId && it.repositoryId == primaryRepoId })
            workspaceRepositoryLinks.attach(workspaceId, primaryRepoId, isPrimary = true)
            workspaceRepositoryLinks.attach(workspaceId, extraRepoId, isPrimary = false)
            workspaceRepositoryLinks.attach(workspaceId, secondExtraRepoId, isPrimary = false)
            lifecycleService.boot(workspaceId, WorkspaceAgentKind.CLAUDE)
        }
    }

    @Test
    fun `handle leaves legacy linkId null when no github_links mirror exists`() {
        // Regression for the production workspace 500: post-V9
        // repositories created directly (no project link first) have
        // no github_links row. The handler used to mirror the repo
        // id into github_link_id unconditionally; the
        // `workspaces.github_link_id → github_links.id` FK then
        // violated and the insert failed with
        // DataIntegrityViolationException → opaque 500. The fix is
        // to leave legacyLinkId null when the mirror row is absent.
        val repoId = RepositoryId.random()
        val repository =
            Repository(
                id = repoId,
                name = "agents",
                repoUrl = "git@github.com:JorisJonkers-dev/agents.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { repositories.findById(repoId) } returns repository
        every { githubLinks.findById(GithubLinkId(repoId.value)) } returns null
        val saved = mutableListOf<Workspace>()
        every { workspaces.save(capture(saved)) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "demo",
                repoUrl = null,
                branch = null,
                repositoryId = repoId,
            ),
        )

        assertThat(saved.first().repoUrl).isEqualTo("git@github.com:JorisJonkers-dev/agents.git")
        assertThat(saved.first().repositoryId).isEqualTo(repoId)
        assertThat(saved.first().githubLinkId).isNull()
    }

    @Test
    fun `legacy githubLinkId create does not attach a synthetic repository id`() {
        val linkId = GithubLinkId.random()
        val link =
            GithubLink(
                id = linkId,
                projectId = ProjectId.random(),
                name = "agents",
                repoUrl = "git@github.com:JorisJonkers-dev/agents.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { githubLinks.findById(linkId) } returns link
        every { repositories.findById(RepositoryId(linkId.value)) } returns null
        every { workspaces.save(any()) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "demo",
                repoUrl = null,
                branch = null,
                githubLinkId = linkId,
            ),
        )

        verify(exactly = 0) { workspaceRepositoryLinks.attach(any(), any(), any()) }
    }

    @Test
    fun `handle SCRATCH kind ignores repoUrl`() {
        val saved = mutableListOf<Workspace>()
        every { workspaces.save(capture(saved)) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "playground",
                repoUrl = "git@github.com:owner/repo.git",
                branch = "main",
                kind = WorkspaceKind.SCRATCH,
            ),
        )

        verify { lifecycleService.boot(any(), any()) }
        assertThat(saved.first().repoUrl).isNull()
        assertThat(saved.first().kind).isEqualTo(WorkspaceKind.SCRATCH)
        verify(exactly = 0) { workspaceRepositoryLinks.attach(any(), any(), any()) }
    }

    @Test
    fun `handle CHAT kind is rejected — chat lives in ChatSession`() {
        assertThrows<IllegalArgumentException> {
            handler.handle(
                CreateWorkspaceCommand(
                    workspaceId = WorkspaceId.random(),
                    name = "chat-but-not",
                    repoUrl = null,
                    branch = null,
                    kind = WorkspaceKind.CHAT,
                ),
            )
        }
    }

    @Test
    fun `unknown repositoryId raises NoSuchElementException with the id in the message`() {
        val repoId = RepositoryId.random()
        every { repositories.findById(repoId) } returns null

        val ex =
            assertThrows<NoSuchElementException> {
                handler.handle(
                    CreateWorkspaceCommand(
                        workspaceId = WorkspaceId.random(),
                        name = "demo",
                        repoUrl = null,
                        branch = null,
                        repositoryId = repoId,
                    ),
                )
            }
        assertThat(ex.message).contains(repoId.value.toString())
    }

    @Test
    fun `unknown githubLinkId raises NoSuchElementException with the id in the message`() {
        val linkId = GithubLinkId.random()
        every { githubLinks.findById(linkId) } returns null

        val ex =
            assertThrows<NoSuchElementException> {
                handler.handle(
                    CreateWorkspaceCommand(
                        workspaceId = WorkspaceId.random(),
                        name = "demo",
                        repoUrl = null,
                        branch = null,
                        githubLinkId = linkId,
                    ),
                )
            }
        assertThat(ex.message).contains(linkId.value.toString())
    }

    @Test
    fun `repositoryId with Vault disabled raises IllegalStateException naming the feature`() {
        // Simulate the @ConditionalOnProperty-wired feature being absent.
        every { repositoryProvider.ifAvailable } returns null
        val repoId = RepositoryId.random()

        val ex =
            assertThrows<IllegalStateException> {
                handler.handle(
                    CreateWorkspaceCommand(
                        workspaceId = WorkspaceId.random(),
                        name = "demo",
                        repoUrl = null,
                        branch = null,
                        repositoryId = repoId,
                    ),
                )
            }
        assertThat(ex.message).contains("Repository feature is not configured")
        assertThat(ex.message).contains(repoId.value.toString())
    }

    @Test
    fun `githubLinkId with Projects feature disabled raises IllegalStateException`() {
        every { linkProvider.ifAvailable } returns null
        val linkId = GithubLinkId.random()

        val ex =
            assertThrows<IllegalStateException> {
                handler.handle(
                    CreateWorkspaceCommand(
                        workspaceId = WorkspaceId.random(),
                        name = "demo",
                        repoUrl = null,
                        branch = null,
                        githubLinkId = linkId,
                    ),
                )
            }
        assertThat(ex.message).contains("Projects feature is not configured")
        assertThat(ex.message).contains(linkId.value.toString())
    }

    @Test
    fun `handle prefers repositoryId when both fields are set`() {
        val repoId = RepositoryId.random()
        val repository =
            Repository(
                id = repoId,
                name = "agents",
                repoUrl = "git@github.com:JorisJonkers-dev/agents.git",
                defaultBranch = "main",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { repositories.findById(repoId) } returns repository
        // The repository-path branch now also probes github_links for
        // the legacy mirror (to decide whether the FK can be set), so
        // mock the "no mirror" case to keep this test focused on the
        // preference assertion.
        every { githubLinks.findById(GithubLinkId(repoId.value)) } returns null
        val saved = mutableListOf<Workspace>()
        every { workspaces.save(capture(saved)) } answers { firstArg() }
        val unrelatedLinkId = GithubLinkId.random()

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "demo",
                repoUrl = null,
                branch = null,
                repositoryId = repoId,
                githubLinkId = unrelatedLinkId,
            ),
        )

        // Repository path was used: the saved row carries the repo's
        // URL and the legacy-link id (which the link-path branch
        // would have resolved differently) was never consulted.
        assertThat(saved.first().repoUrl).isEqualTo(repository.repoUrl)
        verify(exactly = 0) { githubLinks.findById(unrelatedLinkId) }
    }

    @Test
    fun `REPO_BACKED create proceeds even when verification is inconclusive (App grants access, not a key)`() {
        // Access readiness is the GitHub App's concern (surfaced live via
        // install-status), not enforced at create time — so an inconclusive
        // branch-protection check never blocks the create.
        every { verifyAccess.verify(any(), any()) } returns
            VerifyRepositoryAccess.Result(
                defaultBranchProtected = null,
                checkedAt = Instant.now(),
                messages = listOf("branch protection on 'main' could not be determined"),
            )
        every { workspaces.save(any()) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "demo",
                repoUrl = "git@github.com:owner/repo.git",
                branch = null,
            ),
        )

        verify { lifecycleService.boot(any(), any()) }
    }

    @Test
    fun `REPO_BACKED create proceeds when write is false (read-only key is a warning, not a block)`() {
        every { verifyAccess.verify(any(), any()) } returns
            VerifyRepositoryAccess.Result(
                defaultBranchProtected = true,
                checkedAt = Instant.now(),
                messages = listOf("deploy key is read-only — agent commits/pushes will fail: denied"),
            )
        every { workspaces.save(any()) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "demo",
                repoUrl = "git@github.com:owner/repo.git",
                branch = null,
            ),
        )

        verify { lifecycleService.boot(any(), any()) }
    }

    @Test
    fun `REPO_BACKED create proceeds when default branch is unprotected (loud warning, not a block)`() {
        every { verifyAccess.verify(any(), any()) } returns
            VerifyRepositoryAccess.Result(
                defaultBranchProtected = false,
                checkedAt = Instant.now(),
                messages = listOf("default branch 'main' is NOT protected on GitHub"),
            )
        every { workspaces.save(any()) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "demo",
                repoUrl = "git@github.com:owner/repo.git",
                branch = null,
            ),
        )

        verify { lifecycleService.boot(any(), any()) }
    }

    @Test
    fun `REPO_BACKED create proceeds when verify is inconclusive (read null, gateway unavailable)`() {
        every { verifyAccess.verify(any(), any()) } returns
            VerifyRepositoryAccess.Result(
                defaultBranchProtected = null,
                checkedAt = Instant.now(),
                messages = listOf("deploy-key access could not be verified (verify gateway unavailable)"),
            )
        every { workspaces.save(any()) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "demo",
                repoUrl = "git@github.com:owner/repo.git",
                branch = null,
            ),
        )

        verify { lifecycleService.boot(any(), any()) }
    }

    @Test
    fun `SCRATCH create never invokes verify`() {
        every { workspaces.save(any()) } answers { firstArg() }

        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = WorkspaceId.random(),
                name = "playground",
                repoUrl = "git@github.com:owner/repo.git",
                branch = "main",
                kind = WorkspaceKind.SCRATCH,
            ),
        )

        verify(exactly = 0) { verifyAccess.verify(any(), any()) }
    }

    @Test
    fun `workspace row is persisted even when boot fails`() {
        val saved = mutableListOf<Workspace>()
        every { workspaces.save(capture(saved)) } answers { firstArg() }
        every { lifecycleService.boot(any(), any()) } returns
            BootOutcome.Conflict(RunnerUnavailableReason.PROVISION_FAILED)

        val workspaceId = WorkspaceId.random()
        handler.handle(
            CreateWorkspaceCommand(
                workspaceId = workspaceId,
                name = "demo",
                repoUrl = "git@github.com:owner/repo.git",
                branch = null,
            ),
        )

        // workspace was persisted even though boot returned Conflict
        assertThat(saved).hasSize(1)
        assertThat(saved.first().id).isEqualTo(workspaceId)
        assertThat(saved.first().status).isEqualTo(WorkspaceStatus.PENDING)
    }

    private fun repository(
        id: RepositoryId,
        name: String,
    ) = Repository(
        id = id,
        name = name,
        repoUrl = "git@github.com:JorisJonkers-dev/$name.git",
        defaultBranch = "main",
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun setupEntry() =
        AgentSetupCatalogEntry(
            definition =
                AgentSetupDefinition(
                    id = AgentSetupId.default(),
                    version = AgentSetupVersion.initial(),
                    displayName = "Default",
                    description = null,
                    namespace = "agents-system",
                    image = "agent-runner:latest",
                    imagePullPolicy = "IfNotPresent",
                    serviceAccount = "agent-runner",
                    gatewayPort = 8090,
                    claudeCredentialsPvc = "claude",
                    codexCredentialsPvc = "codex",
                    githubDeployKeySecret = "github",
                    cliTools = emptyMap(),
                    knowledgeBaseUrl = "http://knowledge",
                    knowledgeBearerSecret = "knowledge",
                    knowledgeBearerSecretKey = "token",
                    mcpServersConfigMap = "mcp",
                    defaultMcpProfile = "minimal",
                    connectorConfig = emptyMap(),
                    toolProfiles = emptyList(),
                    toolAllowlist = emptyList(),
                    dockerSocketEnabled = false,
                    dockerSocketPath = "/var/run/docker.sock",
                    dockerSocketSupplementalGroups = emptyList(),
                    nodeSelector = emptyMap(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                ),
            availability =
                AgentSetupAvailability(
                    id = AgentSetupId.default(),
                    version = AgentSetupVersion.initial(),
                    selectable = true,
                    defaultSelectable = true,
                    unavailableReason = null,
                    updatedAt = Instant.now(),
                ),
        )
}

package com.jorisjonkers.personalstack.agents.k8s

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import com.jorisjonkers.personalstack.agents.infrastructure.k8s.Fabric8AgentRunnerOrchestrator
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import java.time.Instant
import java.util.UUID

/**
 * End-to-end exercise of [Fabric8AgentRunnerOrchestrator] against a
 * real Kubernetes API supplied by Testcontainers' `k3s` module. The
 * orchestrator's unit-test surface is empty by design — every method
 * delegates to fabric8's builder + `serverSideApply()` chain, which
 * is exactly the seam where #372's RBAC bug (and its predecessors)
 * landed in production.
 *
 * The test class boots a single-node k3s once, applies the canonical
 * RBAC + namespaces, then exercises the orchestrator through a
 * `KubernetesClient` authenticated as the production
 * `agents-system:agents-api` ServiceAccount via the
 * TokenRequest API — so RBAC permissions are enforced identically
 * to production.
 *
 * Scenarios:
 * 1. provision with production RBAC creates pvc + pod + service
 * 2. provision with pre-#372 restricted RBAC fails with patch-forbidden
 * 3. provision is idempotent (server-side apply semantics)
 * 4. destroy removes the four resources
 * 5. provision with a project link stamps a workspace-scoped Secret
 */
@Tag("integration")
@Testcontainers
class Fabric8AgentRunnerOrchestratorIntegrationTest {
    private lateinit var admin: KubernetesClient
    private lateinit var saScoped: KubernetesClient

    @BeforeEach
    fun setUp() {
        admin = K3sTestSupport.createAdminClient(k3s)
        K3sTestSupport.bootstrapNamespacesAndServiceAccount(admin)
    }

    @AfterEach
    fun cleanup() {
        // Wipe orchestrator-owned resources so the next scenario
        // starts clean. RBAC + namespaces stay; per-test RBAC
        // variations overwrite the Role via server-side apply.
        admin.pods().inNamespace(K3sTestSupport.AGENTS_NAMESPACE).delete()
        admin.services().inNamespace(K3sTestSupport.AGENTS_NAMESPACE).delete()
        admin.persistentVolumeClaims().inNamespace(K3sTestSupport.AGENTS_NAMESPACE).delete()
        admin
            .secrets()
            .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
            .withLabel("app.kubernetes.io/part-of", "agent-runner")
            .delete()
        if (this::saScoped.isInitialized) saScoped.close()
        admin.close()
    }

    @Test
    @DisplayName("provision with production RBAC creates PVC + Pod + Service")
    fun `provision with production RBAC creates pvc pod service`() {
        K3sTestSupport.applyProductionRbac(admin)
        saScoped = K3sTestSupport.createServiceAccountScopedClient(k3s, admin)
        val orchestrator = orchestrator(saScoped, deployKeysProvider = empty(), githubLinks = empty())
        val workspace = adHocWorkspace()

        orchestrator.provision(workspace)

        val short = workspace.id.short()
        assertThat(
            admin
                .persistentVolumeClaims()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("workspace-$short")
                .get(),
        ).isNotNull
        assertThat(
            admin
                .pods()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-$short")
                .get(),
        ).isNotNull
        assertThat(
            admin
                .services()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-$short")
                .get(),
        ).isNotNull
    }

    @Test
    @DisplayName("provisioned Pod carries KB_URL + KB_BEARER_TOKEN so the knowledge hooks can authenticate")
    fun `provisioned pod carries knowledge mcp env`() {
        K3sTestSupport.applyProductionRbac(admin)
        saScoped = K3sTestSupport.createServiceAccountScopedClient(k3s, admin)
        val orchestrator = orchestrator(saScoped, deployKeysProvider = empty(), githubLinks = empty())
        val workspace = adHocWorkspace()

        orchestrator.provision(workspace)

        val env =
            admin
                .pods()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-${workspace.id.short()}")
                .get()
                .spec
                .containers
                .single()
                .env
        val kbUrl = env.single { it.name == "KB_URL" }
        assertThat(kbUrl.value).isEqualTo("http://knowledge-api.knowledge-system.svc.cluster.local:8080")
        val bearer = env.single { it.name == "KB_BEARER_TOKEN" }
        // Sourced from the Secret, never hard-coded as a literal value.
        assertThat(bearer.value).isNull()
        assertThat(bearer.valueFrom.secretKeyRef.name).isEqualTo("agents-kb-bearer")
        assertThat(bearer.valueFrom.secretKeyRef.key).isEqualTo("bearer")

        // IS_SANDBOX tells Claude Code it is sandboxed so it skips the
        // bypass-permissions warning/acceptance.
        assertThat(env.single { it.name == "IS_SANDBOX" }.value).isEqualTo("1")
        assertThat(env.single { it.name == "DOCKER_HOST" }.value).isEqualTo("unix:///var/run/docker.sock")
        assertThat(env.single { it.name == "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE" }.value)
            .isEqualTo("/var/run/docker.sock")
        val nodeHostIp = env.single { it.name == "AGENT_RUNNER_NODE_HOST_IP" }
        assertThat(nodeHostIp.value).isNull()
        assertThat(nodeHostIp.valueFrom.fieldRef.fieldPath).isEqualTo("status.hostIP")
        assertThat(env.single { it.name == "TESTCONTAINERS_HOST_OVERRIDE" }.value)
            .isEqualTo("$(AGENT_RUNNER_NODE_HOST_IP)")
        // REPO_URL + REPO_BRANCH drive the entrypoint's boot-time clone
        // (adHocWorkspace is repo-backed with a branch).
        assertThat(env.single { it.name == "REPO_URL" }.value).isEqualTo("git@github.com:example/repo.git")
        assertThat(env.single { it.name == "REPO_BRANCH" }.value).isEqualTo("main")

        // GITHUB_APP_TOKEN_URL + GITHUB_APP_TOKEN_BEARER let the runner's
        // `gh` wrapper mint repo-scoped App tokens; the bearer is an
        // optional Secret ref, never a literal.
        val expectedTokenUrl =
            "http://agents-api.agents-system.svc.cluster.local:8082" +
                "/api/v1/internal/github/installation-token"
        assertThat(env.single { it.name == "GITHUB_APP_TOKEN_URL" }.value).isEqualTo(expectedTokenUrl)
        val appBearer = env.single { it.name == "GITHUB_APP_TOKEN_BEARER" }
        assertThat(appBearer.value).isNull()
        assertThat(appBearer.valueFrom.secretKeyRef.name).isEqualTo("github-app")
        assertThat(appBearer.valueFrom.secretKeyRef.key).isEqualTo("token-bearer")
        assertThat(appBearer.valueFrom.secretKeyRef.optional).isTrue()

        // The agents-mcp-servers ConfigMap is mounted (optionally) at
        // /etc/agent-mcp so the entrypoint can seed Claude's mcpServers.
        val pod =
            admin
                .pods()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-${workspace.id.short()}")
                .get()
        assertThat(pod.spec.nodeSelector)
            .containsEntry("agents/node", "enschede-gtx-960m-1")
            .containsEntry("agents/capability-docker-socket", "true")
        val mcpMount =
            pod.spec.containers
                .single()
                .volumeMounts
                .single { it.name == "mcp-config" }
        assertThat(mcpMount.mountPath).isEqualTo("/etc/agent-mcp")
        val mcpVol = pod.spec.volumes.single { it.name == "mcp-config" }
        assertThat(mcpVol.configMap.name).isEqualTo("agents-mcp-servers")
        assertThat(mcpVol.configMap.optional).isTrue()
        val dockerMount =
            pod.spec.containers
                .single()
                .volumeMounts
                .single { it.name == "docker-socket" }
        assertThat(dockerMount.mountPath).isEqualTo("/var/run/docker.sock")
        val dockerVol = pod.spec.volumes.single { it.name == "docker-socket" }
        assertThat(dockerVol.hostPath.path).isEqualTo("/var/run/docker.sock")
        assertThat(dockerVol.hostPath.type).isEqualTo("Socket")

        // The runner must hold no Kubernetes API credential: cluster reads
        // go through the read-only MCP server, never the SA token. An
        // unsandboxed agent therefore cannot reach the API server to
        // modify or delete any Pod.
        assertThat(pod.spec.automountServiceAccountToken).isFalse()
        assertThat(pod.spec.securityContext.supplementalGroups)
            .contains(131L)
            .doesNotContain(998L, 999L)

        // A startup probe must gate liveness so the gateway's JVM cold
        // start is not killed mid-boot (which re-provisioned the runner
        // and 503'd start-session).
        val container = pod.spec.containers.single()
        assertThat(container.startupProbe).isNotNull
        assertThat(container.startupProbe.httpGet.path).isEqualTo("/healthz")
        assertThat(container.startupProbe.failureThreshold).isEqualTo(60)
        assertThat(container.livenessProbe).isNotNull
    }

    @Test
    @DisplayName("provision with pre-#372 restricted RBAC fails with PVC patch-forbidden")
    fun `provision with pre #372 restricted RBAC fails with patch forbidden on pvc`() {
        K3sTestSupport.applyPre372RestrictedRbac(admin)
        saScoped = K3sTestSupport.createServiceAccountScopedClient(k3s, admin)
        val orchestrator = orchestrator(saScoped, deployKeysProvider = empty(), githubLinks = empty())

        assertThatThrownBy { orchestrator.provision(adHocWorkspace()) }
            .isInstanceOf(KubernetesClientException::class.java)
            .hasMessageContaining("cannot patch resource \"persistentvolumeclaims\"")
    }

    @Test
    @DisplayName("provision is idempotent — calling twice does not error")
    fun `provision is idempotent`() {
        K3sTestSupport.applyProductionRbac(admin)
        saScoped = K3sTestSupport.createServiceAccountScopedClient(k3s, admin)
        val orchestrator = orchestrator(saScoped, deployKeysProvider = empty(), githubLinks = empty())
        val workspace = adHocWorkspace()

        orchestrator.provision(workspace)
        orchestrator.provision(workspace)

        assertThat(
            admin
                .pods()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-${workspace.id.short()}")
                .get(),
        ).isNotNull
    }

    @Test
    @DisplayName("scaleDown removes Pod and Service but keeps the PVC")
    fun `scaleDown removes pod and service but keeps the pvc`() {
        K3sTestSupport.applyProductionRbac(admin)
        saScoped = K3sTestSupport.createServiceAccountScopedClient(k3s, admin)
        val orchestrator = orchestrator(saScoped, deployKeysProvider = empty(), githubLinks = empty())
        val workspace = adHocWorkspace()
        orchestrator.provision(workspace)

        orchestrator.scaleDown(workspace)

        val short = workspace.id.short()
        // PVC must survive scale-down so a follow-up provision can re-attach it.
        assertThat(
            admin
                .persistentVolumeClaims()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("workspace-$short")
                .get(),
        ).isNotNull
        // Pod and Service are torn down.
        assertDeletedOrTerminating {
            admin
                .pods()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-$short")
                .get()
                ?.metadata
                ?.deletionTimestamp
        }
        assertDeletedOrTerminating {
            admin
                .services()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-$short")
                .get()
                ?.metadata
                ?.deletionTimestamp
        }
    }

    @Test
    @DisplayName("destroy removes the PVC, Pod, and Service")
    fun `destroy removes the four resources`() {
        K3sTestSupport.applyProductionRbac(admin)
        saScoped = K3sTestSupport.createServiceAccountScopedClient(k3s, admin)
        val orchestrator = orchestrator(saScoped, deployKeysProvider = empty(), githubLinks = empty())
        val workspace = adHocWorkspace()
        orchestrator.provision(workspace)

        orchestrator.destroy(workspace)

        // Deletes are async in Kubernetes — the `pvc-protection`
        // finalizer + Pod garbage collection keep `get()` returning
        // a still-Terminating object for a few hundred ms. We assert
        // either disappearance (gc complete) or a non-null
        // `deletionTimestamp` (gc in progress), both of which prove
        // the orchestrator successfully issued the delete.
        val short = workspace.id.short()
        assertDeletedOrTerminating {
            admin
                .persistentVolumeClaims()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("workspace-$short")
                .get()
                ?.metadata
                ?.deletionTimestamp
        }
        assertDeletedOrTerminating {
            admin
                .pods()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-$short")
                .get()
                ?.metadata
                ?.deletionTimestamp
        }
        assertDeletedOrTerminating {
            admin
                .services()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-$short")
                .get()
                ?.metadata
                ?.deletionTimestamp
        }
    }

    /**
     * Resource is considered deleted if it's either gone (`get()`
     * returns null, the supplier returns null because of the safe
     * navigation chain) or in the Terminating phase (`deletionTimestamp`
     * non-null). Either proves the orchestrator issued the delete;
     * the actual gc-completion isn't the orchestrator's responsibility.
     */
    private fun assertDeletedOrTerminating(deletionTimestamp: () -> String?) {
        // Poll briefly in case the API hasn't materialised the
        // deletionTimestamp on the next GET yet — single-digit ms
        // typically.
        repeat(POLL_ATTEMPTS) {
            val ts = deletionTimestamp()
            if (ts != null) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        // Final read — if it's null here, the resource is fully gone
        // (deleted + gc'd), which is also acceptable.
        assertThat(deletionTimestamp()).isNull()
    }

    @Test
    @DisplayName("provision with project link stamps a workspace-scoped Secret")
    fun `provision with project link stamps workspace scoped secret`() {
        K3sTestSupport.applyProductionRbac(admin)
        saScoped = K3sTestSupport.createServiceAccountScopedClient(k3s, admin)

        val projectId = ProjectId(UUID.randomUUID())
        val linkId = GithubLinkId.random()
        val link =
            GithubLink(
                id = linkId,
                projectId = projectId,
                name = "test-repo",
                repoUrl = "git@github.com:example/test-repo.git",
                defaultBranch = "main",
                vaultKeyPath = "secret/agents/projects/$projectId/repos/$linkId",
                deployKeyFingerprint = "SHA256:test",
                deployKeyAddedAt = Instant.now(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        val keys = StaticDeployKeyStore(linkId, EXAMPLE_KEY_MATERIAL)
        val links = SingleEntryGithubLinkRepo(link)
        val orchestrator = orchestrator(saScoped, deployKeysProvider = wrap(keys), githubLinks = wrap(links))

        val workspace = adHocWorkspace().copy(githubLinkId = linkId)
        orchestrator.provision(workspace)

        val short = workspace.id.short()
        val secret =
            admin
                .secrets()
                .inNamespace(K3sTestSupport.AGENTS_NAMESPACE)
                .withName("agent-runner-deploy-key-$short")
                .get()
        assertThat(secret).isNotNull
        assertThat(secret.metadata.labels["agent-runner/github-link-id"]).isEqualTo(linkId.toString())
        assertThat(secret.data).containsKeys("private_key", "public_key", "known_hosts", "fingerprint")
    }

    private fun orchestrator(
        client: KubernetesClient,
        deployKeysProvider: ObjectProvider<DeployKeyStore>,
        githubLinks: ObjectProvider<GithubLinkRepository>,
        workspaceRepos: ObjectProvider<WorkspaceRepositoryRepository> = empty(),
        repositories: ObjectProvider<RepositoryRepository> = empty(),
    ): Fabric8AgentRunnerOrchestrator =
        Fabric8AgentRunnerOrchestrator(
            client = client,
            props = testProps(),
            deployKeysProvider = deployKeysProvider,
            githubLinks = githubLinks,
            workspaceRepos = workspaceRepos,
            repositories = repositories,
        )

    private fun testProps(): AgentRuntimeProperties =
        AgentRuntimeProperties(
            namespace = K3sTestSupport.AGENTS_NAMESPACE,
            image = "ghcr.io/extratoast/agents/agent-runner:latest",
            serviceAccount = "agent-runner",
            workspaceStorageClass = "local-path",
            claudeCredentialsPvc = "claude-credentials",
            codexCredentialsPvc = "codex-credentials",
            githubDeployKeySecret = "agents-github-deploy-key",
        )

    private fun adHocWorkspace(): Workspace =
        Workspace(
            id = WorkspaceId.random(),
            name = "integration-test",
            repoUrl = "git@github.com:example/repo.git",
            branch = "main",
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.PENDING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private class StaticDeployKeyStore(
        private val linkId: GithubLinkId,
        private val material: DeployKeyStore.KeyMaterial,
    ) : DeployKeyStore {
        override fun store(
            projectId: ProjectId,
            linkId: GithubLinkId,
            privateKeyOpenssh: String,
            publicKeyOpenssh: String,
            knownHosts: String,
        ): DeployKeyStore.StoredKey = error("not used in this test")

        override fun remove(
            projectId: ProjectId,
            linkId: GithubLinkId,
        ) = error("not used in this test")

        override fun readPublicKey(
            projectId: ProjectId,
            linkId: GithubLinkId,
        ): String? = material.publicKey

        override fun loadKey(
            projectId: ProjectId,
            linkId: GithubLinkId,
        ): DeployKeyStore.KeyMaterial? = if (linkId == this.linkId) material else null

        override fun store(
            repositoryId: com.jorisjonkers.personalstack.agents.domain.model.RepositoryId,
            privateKeyOpenssh: String,
            publicKeyOpenssh: String,
            knownHosts: String,
        ): DeployKeyStore.StoredKey = error("not used in this test")

        override fun remove(repositoryId: com.jorisjonkers.personalstack.agents.domain.model.RepositoryId) =
            error("not used in this test")

        override fun readPublicKey(
            repositoryId: com.jorisjonkers.personalstack.agents.domain.model.RepositoryId,
        ): String? = material.publicKey

        override fun loadKey(
            repositoryId: com.jorisjonkers.personalstack.agents.domain.model.RepositoryId,
        ): DeployKeyStore.KeyMaterial? = if (repositoryId.value == linkId.value) material else null
    }

    private class SingleEntryGithubLinkRepo(
        private val link: GithubLink,
    ) : GithubLinkRepository {
        override fun save(link: GithubLink): GithubLink = error("not used in this test")

        override fun findById(id: GithubLinkId): GithubLink? = if (id == link.id) link else null

        override fun findAllByProjectId(projectId: ProjectId): List<GithubLink> =
            if (projectId == link.projectId) listOf(link) else emptyList()

        override fun delete(id: GithubLinkId) = error("not used in this test")
    }

    /**
     * Minimal `ObjectProvider` impl. The orchestrator only ever
     * reads `.ifAvailable`, so the unused defaults are fine — but
     * we still throw helpfully on the methods that don't fit a
     * "single optional value" mental model so a regression that
     * starts calling them surfaces clearly.
     */
    private class SingleValueObjectProvider<T : Any>(
        private val value: T?,
    ) : ObjectProvider<T> {
        override fun getObject(): T = value ?: error("no value provided")

        override fun getObject(vararg args: Any?): T = getObject()

        override fun getIfAvailable(): T? = value

        override fun getIfUnique(): T? = value

        override fun iterator(): MutableIterator<T> =
            (if (value != null) mutableListOf(value) else mutableListOf()).iterator()
    }

    private fun <T : Any> empty(): ObjectProvider<T> = SingleValueObjectProvider(null)

    private fun <T : Any> wrap(value: T): ObjectProvider<T> = SingleValueObjectProvider(value)

    companion object {
        // Singleton k3s container per test class. `@JvmStatic` lets the
        // Testcontainers JUnit-Jupiter extension recognise it as a
        // class-level container and start it in `beforeAll`, before
        // any user lifecycle method runs.
        @JvmStatic
        @Container
        private val k3s: K3sContainer = K3sTestSupport.newContainer()

        private const val POLL_ATTEMPTS = 20
        private const val POLL_INTERVAL_MS = 50L

        // Synthetic key material — the orchestrator never parses it,
        // just base64-encodes the bytes into a k8s Secret. The
        // explicit "NOT-A-REAL-KEY" string keeps secret scanners
        // from flagging the file.
        private val EXAMPLE_KEY_MATERIAL =
            DeployKeyStore.KeyMaterial(
                privateKey = "TEST-PRIVATE-KEY-NOT-A-REAL-KEY",
                publicKey = "TEST-PUBLIC-KEY-NOT-A-REAL-KEY",
                knownHosts = "github.com ssh-rsa AAAA-test-only",
                fingerprint = "SHA256:test",
            )
    }
}

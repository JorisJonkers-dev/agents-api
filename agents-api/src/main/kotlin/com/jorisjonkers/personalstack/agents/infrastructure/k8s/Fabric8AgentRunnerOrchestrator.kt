package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.RunnerState
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * Owns the entire lifecycle of one workspace's runner Pod. The shape
 * is fixed: one Pod (`agent-runner-<short-id>`), one workspace PVC
 * (`workspace-<short-id>`), one ClusterIP Service so the gateway has
 * a stable in-cluster name. Adversarial use is anticipated, so the
 * Pod runs as UID 1000, with read-only mounts for the credential
 * PVCs that the agent itself doesn't need to write. Docker socket
 * access is intentionally host-equivalent and exists so repo tests
 * using Testcontainers can run inside future agent sessions.
 *
 * Per-workspace deploy-key isolation: when the workspace's
 * `githubLinkId` is set, the orchestrator looks up the link, reads
 * the deploy key from Vault via [DeployKeyStore], and stamps a
 * workspace-scoped k8s Secret out of it. The Pod mounts that Secret
 * instead of the cluster-wide `agents-github-deploy-key`, so a
 * workspace can only ever push to its own Project's repos.
 *
 * fabric8's fluent builder chains naturally split into one helper per
 * pod section (labels, env, mounts, volumes, container body); below
 * the 15-function / LargeClass detekt thresholds would defeat the
 * readability win. The class stays as a single orchestrator because
 * every helper here operates on the same shared props + client.
 */
@Suppress("TooManyFunctions", "LargeClass", "DEPRECATION")
@Component
@Profile("!system-test")
class Fabric8AgentRunnerOrchestrator(
    private val client: KubernetesClient,
    private val props: AgentRuntimeProperties,
    private val deployKeysProvider: ObjectProvider<DeployKeyStore>,
    private val githubLinks: ObjectProvider<GithubLinkRepository>,
    private val workspaceRepos: ObjectProvider<WorkspaceRepositoryRepository>,
    private val repositories: ObjectProvider<RepositoryRepository>,
) : AgentRunnerOrchestrator {
    private val log = LoggerFactory.getLogger(Fabric8AgentRunnerOrchestrator::class.java)

    override fun provision(workspace: Workspace): AgentRunnerOrchestrator.RunnerHandle =
        provision(workspace, legacySetupSpec(), workspace.runnerSetupGeneration)

    override fun provision(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
    ): AgentRunnerOrchestrator.RunnerHandle {
        val short = workspace.id.short()
        val podName = "agent-runner-$short"
        val pvcName = "workspace-$short"
        val serviceName = "agent-runner-$short"
        val deployKeySecretName = ensureDeployKeySecret(workspace, short, setup)
        applyResources(workspace, setup, runnerGeneration, podName, pvcName, serviceName, deployKeySecretName)
        val endpoint = "http://$serviceName.${props.namespace}.svc.cluster.local:${setup.gatewayPort}"
        log.info(
            "provisioned runner pod {} for workspace {} using setup {}@{} generation {} and deploy-key Secret {}",
            podName,
            workspace.id,
            setup.setupId,
            setup.setupVersion,
            runnerGeneration,
            deployKeySecretName,
        )
        return AgentRunnerOrchestrator.RunnerHandle(
            podName = podName,
            pvcName = pvcName,
            gatewayEndpoint = endpoint,
        )
    }

    private fun applyResources(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
        podName: String,
        pvcName: String,
        serviceName: String,
        deployKeySecretName: String,
    ) {
        client
            .persistentVolumeClaims()
            .inNamespace(props.namespace)
            .resource(pvc(pvcName))
            .serverSideApply()
        client
            .pods()
            .inNamespace(props.namespace)
            .resource(pod(workspace, setup, runnerGeneration, podName, pvcName, deployKeySecretName))
            .serverSideApply()
        client
            .services()
            .inNamespace(props.namespace)
            .resource(service(serviceName, podName, setup.gatewayPort))
            .serverSideApply()
    }

    override fun scaleDown(workspace: Workspace) {
        val short = workspace.id.short()
        client
            .pods()
            .inNamespace(props.namespace)
            .withName("agent-runner-$short")
            .delete()
        client
            .services()
            .inNamespace(props.namespace)
            .withName("agent-runner-$short")
            .delete()
        log.info("scaled down runner pod for workspace {} (PVC preserved)", workspace.id)
    }

    override fun destroy(workspace: Workspace) {
        val short = workspace.id.short()
        client
            .pods()
            .inNamespace(props.namespace)
            .withName("agent-runner-$short")
            .delete()
        client
            .services()
            .inNamespace(props.namespace)
            .withName("agent-runner-$short")
            .delete()
        client
            .persistentVolumeClaims()
            .inNamespace(props.namespace)
            .withName("workspace-$short")
            .delete()
        // Per-workspace deploy-key Secret only exists when the workspace was bound to a GithubLink.
        if (workspace.githubLinkId != null) {
            client
                .secrets()
                .inNamespace(props.namespace)
                .withName(workspaceSecretName(short))
                .delete()
        }
        log.info("destroyed runner pod and PVC for workspace {}", workspace.id)
    }

    override fun runnerState(workspace: Workspace): RunnerState? {
        val name = workspace.podName ?: return null
        val pod =
            client
                .pods()
                .inNamespace(props.namespace)
                .withName(name)
                .get() ?: return null
        return runnerState(pod)
    }

    override fun isRunnerImageStale(workspace: Workspace): Boolean {
        // Stale when the runner's pinned version differs from the release
        // agents-api itself is on. Unknown on either side → never recycle.
        val current = runnerImageVersion(workspace) ?: return false
        val target = targetRunnerImageVersion() ?: return false
        return current != target
    }

    override fun runnerImageVersion(workspace: Workspace): String? = runnerState(workspace)?.runnerImageVersion

    override fun targetRunnerImageVersion(): String? = ownReleaseVersion()

    /**
     * The release version this agents-api process is running, baked into the
     * image as `SERVICE_VERSION` (= the release-please tag, e.g. "v0.12.0").
     * The whole suite is published in lockstep, so this is also the target
     * agent-runner version. Null on a local/dev build where it is unset or
     * "unknown", which disables upgrade detection rather than guessing.
     */
    private fun ownReleaseVersion(): String? =
        System.getenv("SERVICE_VERSION")?.takeIf { it.isNotBlank() && it != "unknown" }

    override fun isReady(workspace: Workspace): Boolean {
        val state = runnerState(workspace) ?: return false
        return state.containerReady &&
            state.phase == "Running" &&
            state.matches(
                setupId = workspace.currentRunnerSetupId,
                setupVersion = workspace.currentRunnerSetupVersion,
                runnerGeneration = workspace.runnerSetupGeneration,
            )
    }

    override fun isReady(
        workspace: Workspace,
        expectedIdentity: RunnerState.Identity,
    ): Boolean {
        val state = runnerState(workspace) ?: return false
        return state.containerReady && state.phase == "Running" && state.matches(expectedIdentity)
    }

    private fun runnerState(pod: Pod): RunnerState {
        val containerReady =
            pod.status
                ?.containerStatuses
                ?.firstOrNull()
                ?.ready ?: false
        val labels = pod.metadata?.labels.orEmpty()
        val annotations = pod.metadata?.annotations.orEmpty()
        return RunnerState(
            podName = pod.metadata?.name.orEmpty(),
            workspaceId = labels[RunnerState.LABEL_WORKSPACE_ID],
            setupId = labels[RunnerState.LABEL_SETUP_ID]?.let(::parseSetupId),
            setupVersion = labels[RunnerState.LABEL_SETUP_VERSION]?.let(::parseSetupVersion),
            setupHash = annotations[RunnerState.ANNOTATION_SETUP_HASH],
            runnerGeneration = labels[RunnerState.LABEL_RUNNER_GENERATION]?.toLongOrNull(),
            phase = pod.status?.phase,
            containerReady = containerReady,
            runnerImageVersion =
                RunnerImageVersions.tagOf(
                    pod.spec
                        ?.containers
                        ?.firstOrNull()
                        ?.image,
                ),
        )
    }

    private fun parseSetupId(value: String): AgentSetupId? = runCatching { AgentSetupId(value) }.getOrNull()

    private fun parseSetupVersion(value: String): AgentSetupVersion? =
        value.toLongOrNull()?.let { runCatching { AgentSetupVersion(it) }.getOrNull() }

    private fun workspaceSecretName(short: String): String = "agent-runner-deploy-key-$short"

    /**
     * Resolve the deploy-key Secret name for a workspace. If the
     * workspace is project-backed and the linked GithubLink has a
     * Vault-stored key, that key gets stamped into a workspace-
     * scoped Secret and its name is returned. Every other path —
     * no link, link without key, Vault adapter disabled — falls
     * back to the shared cluster-wide Secret so the Pod can still
     * come up.
     */
    private fun ensureDeployKeySecret(
        workspace: Workspace,
        short: String,
        setup: RunnerSetupProvisioningSpec,
    ): String {
        val material = resolveKeyMaterial(workspace) ?: return setup.githubDeployKeySecret
        val secretName = workspaceSecretName(short)
        val secret = buildWorkspaceSecret(secretName, short, material.link, material.key)
        client
            .secrets()
            .inNamespace(props.namespace)
            .resource(secret)
            .serverSideApply()
        return secretName
    }

    private data class ResolvedKey(
        val link: GithubLink,
        val key: DeployKeyStore.KeyMaterial,
    )

    // Early-out validation chain — explicit guards read better than
    // a chained Result here; suppressing detekt's bounded-return
    // and bounded-function rules with intent.
    @Suppress("ReturnCount")
    private fun resolveKeyMaterial(workspace: Workspace): ResolvedKey? {
        val linkId = workspace.githubLinkId ?: return null
        val links = githubLinks.ifAvailable ?: return null
        val keys = deployKeysProvider.ifAvailable ?: return null
        val link =
            links.findById(linkId).also {
                if (it == null) log.warn("workspace {} references missing GithubLink {}", workspace.id, linkId)
            } ?: return null
        val key =
            keys.loadKey(link.projectId, link.id).also {
                if (it == null) log.warn("workspace {} link {} has no Vault key yet", workspace.id, linkId)
            } ?: return null
        return ResolvedKey(link, key)
    }

    private fun buildWorkspaceSecret(
        name: String,
        short: String,
        link: GithubLink,
        material: DeployKeyStore.KeyMaterial,
    ): Secret {
        // fabric8 7.x's typed-builder `withLabels` / `withData`
        // resolve cleanly only when the `Map` literal lives inline
        // at the call site; an extracted `val labels: Map<String,
        // String>` makes Kotlin's overload-resolution choke on K/V
        // type parameters. Inlining is the pragmatic fix.
        return SecretBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(props.namespace)
            .withLabels<String, String>(
                mapOf(
                    "app.kubernetes.io/part-of" to "agent-runner",
                    "agent-runner/workspace-id" to short,
                    "agent-runner/github-link-id" to link.id.toString(),
                ),
            ).endMetadata()
            .withType("Opaque")
            .withData<String, String>(
                mapOf(
                    "private_key" to b64(material.privateKey),
                    "public_key" to b64(material.publicKey),
                    "known_hosts" to b64(material.knownHosts),
                    "fingerprint" to b64(material.fingerprint),
                ),
            ).build()
    }

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun pvc(name: String): PersistentVolumeClaim =
        PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(props.namespace)
            .endMetadata()
            .withNewSpec()
            .withAccessModes("ReadWriteOnce")
            .withStorageClassName(props.workspaceStorageClass)
            .withNewResources()
            .withRequests<String, Quantity>(mapOf("storage" to Quantity(props.workspaceStorageSize)))
            .endResources()
            .endSpec()
            .build()

    // fabric8 builder chains can't be cleanly split into helpers
    // because the intermediate fluent types are private; LongMethod
    // is the natural shape here and suppressed with intent. The
    // inline `mapOf(...)` calls are likewise intentional: extracting
    // them into typed vals trips Kotlin overload resolution on
    // fabric8 7.x's `withLabels` / `withRequests` / `withLimits`.
    @Suppress("LongMethod")
    private fun pod(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
        name: String,
        workspacePvc: String,
        deployKeySecret: String,
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(props.namespace)
            .withLabels<String, String>(podLabels(workspace, setup, runnerGeneration))
            .withAnnotations<String, String>(podAnnotations(setup))
            .endMetadata()
            .withNewSpec()
            .withServiceAccountName(setup.serviceAccount)
            // The runner never calls the Kubernetes API itself — cluster
            // reads go through the read-only kubernetes MCP server over
            // HTTP. Don't project the SA token into the Pod, so the agent
            // (which runs unsandboxed) holds no API credential at all and
            // cannot reach the API server even if the SA were later granted
            // RBAC by mistake.
            .withAutomountServiceAccountToken(false)
            .withNodeSelector<String, String>(setup.nodeSelector)
            .withRestartPolicy("Always")
            .withNewSecurityContext()
            .withRunAsUser(RUN_AS_UID)
            .withRunAsGroup(RUN_AS_GID)
            .withFsGroup(FS_GROUP)
            .withSupplementalGroups(podSupplementalGroups(setup))
            .endSecurityContext()
            .addNewContainer()
            .withName("agent-runner")
            // Pin to the release agents-api itself is on so the running
            // version is a verifiable fact in the Pod spec, not :latest.
            .withImage(RunnerImageVersions.pin(setup.image, ownReleaseVersion()))
            .withImagePullPolicy(setup.imagePullPolicy)
            .withPorts(ContainerPortBuilder().withName("gateway").withContainerPort(setup.gatewayPort).build())
            .withEnv(podEnv(workspace, setup, runnerGeneration))
            .withVolumeMounts(podVolumeMounts(setup))
            // Startup probe gates liveness + readiness until the gateway's
            // JVM has finished its cold start. Without it the liveness probe
            // (failureThreshold 3 x 10s ~= 30s, no initial delay) killed the
            // booting Spring Boot gateway before it bound :8090, which
            // re-provisioned the runner in a loop and 503'd every
            // start-session. 60 x 5s = 5 min of boot headroom.
            .withNewStartupProbe()
            .withNewHttpGet()
            .withPath("/healthz")
            .withNewPort("gateway")
            .endHttpGet()
            .withPeriodSeconds(STARTUP_PERIOD_SECONDS)
            .withFailureThreshold(STARTUP_FAILURE_THRESHOLD)
            .endStartupProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/healthz")
            .withNewPort("gateway")
            .endHttpGet()
            .withPeriodSeconds(READINESS_PERIOD_SECONDS)
            .withFailureThreshold(READINESS_FAILURE_THRESHOLD)
            .endReadinessProbe()
            .withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/healthz")
            .withNewPort("gateway")
            .endHttpGet()
            .withPeriodSeconds(LIVENESS_PERIOD_SECONDS)
            .endLivenessProbe()
            .withNewResources()
            .withRequests<String, Quantity>(mapOf("cpu" to Quantity(CPU_REQUEST), "memory" to Quantity(MEMORY_REQUEST)))
            .withLimits<String, Quantity>(mapOf("cpu" to Quantity(CPU_LIMIT), "memory" to Quantity(MEMORY_LIMIT)))
            .endResources()
            .endContainer()
            .withVolumes(podVolumes(workspacePvc, deployKeySecret, setup))
            .endSpec()
            .build()

    private fun podLabels(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
    ): Map<String, String> =
        mapOf(
            "app.kubernetes.io/name" to "agent-runner",
            "app.kubernetes.io/part-of" to "agent-runner",
            RunnerState.LABEL_WORKSPACE_ID to workspace.id.short(),
            RunnerState.LABEL_SETUP_ID to setup.setupId.value,
            RunnerState.LABEL_SETUP_VERSION to setup.setupVersion.value.toString(),
            RunnerState.LABEL_RUNNER_GENERATION to runnerGeneration.toString(),
        )

    private fun podAnnotations(setup: RunnerSetupProvisioningSpec): Map<String, String> =
        mapOf(RunnerState.ANNOTATION_SETUP_HASH to setup.setupHash)

    @Suppress("LongMethod")
    private fun podEnv(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
    ) = buildList {
        add(EnvVarBuilder().withName("HOME").withValue("/home/agent").build())
        add(EnvVarBuilder().withName("CODEX_HOME").withValue("/home/agent/.codex").build())
        add(EnvVarBuilder().withName("DEPLOYMENT_ENVIRONMENT").withValue("production").build())
        add(EnvVarBuilder().withName("OTEL_SERVICE_NAME").withValue("agent-gateway").build())
        add(
            EnvVarBuilder()
                .withName("OTEL_EXPORTER_OTLP_ENDPOINT")
                .withValue("http://alloy.observability.svc.cluster.local:4318")
                .build(),
        )
        add(EnvVarBuilder().withName("OTEL_EXPORTER_OTLP_PROTOCOL").withValue("http/protobuf").build())
        // The runner Pod is the outer sandbox for the agent process.
        // Docker socket access is the explicit host-equivalent exception
        // for Testcontainers and Docker CLI workflows. IS_SANDBOX tells
        // Claude Code so that --dangerously-skip-permissions runs without
        // the bypass-mode warning + acceptance prompt.
        add(EnvVarBuilder().withName("IS_SANDBOX").withValue("1").build())
        add(EnvVarBuilder().withName("AGENT_MCP_PROFILE").withValue(setup.mcpProfile).build())
        add(EnvVarBuilder().withName("AGENT_MCP_DIR").withValue(setup.mcpDir).build())
        setup.claudeMcpServersFile?.let {
            add(EnvVarBuilder().withName("AGENT_MCP_SERVERS_FILE").withValue(it).build())
        }
        setup.codexMcpServersFile?.let {
            add(EnvVarBuilder().withName("AGENT_CODEX_MCP_FILE").withValue(it).build())
        }
        setup.githubMcpToolsets?.let {
            add(EnvVarBuilder().withName("GITHUB_MCP_TOOLSETS").withValue(it).build())
        }
        setup.githubMcpExcludeTools?.let {
            add(EnvVarBuilder().withName("GITHUB_MCP_EXCLUDE_TOOLS").withValue(it).build())
        }
        add(EnvVarBuilder().withName("AGENT_GATEWAY_RUNNER_SETUP_ID").withValue(setup.setupId.value).build())
        add(
            EnvVarBuilder()
                .withName("AGENT_GATEWAY_RUNNER_SETUP_VERSION")
                .withValue(setup.setupVersion.value.toString())
                .build(),
        )
        add(EnvVarBuilder().withName("AGENT_GATEWAY_RUNNER_SETUP_HASH").withValue(setup.setupHash).build())
        add(
            EnvVarBuilder()
                .withName("AGENT_GATEWAY_RUNNER_GENERATION")
                .withValue(runnerGeneration.toString())
                .build(),
        )
        addAll(dockerEnv(setup))
        addAll(knowledgeEnv(setup))
        addAll(githubAppTokenEnv())
        addAll(claudeOauthEnv())
        // REPO_URL/REPO_BRANCH drive the entrypoint's boot-time clone
        // into /workspace/<repo-name>. Cloning in the runner removes the race that
        // left repo-backed workspaces empty: the old create-time
        // gateway.clone fired before the runner gateway was up and was
        // swallowed. Only repo-backed workspaces carry a repoUrl.
        workspace.repoUrl?.let { url ->
            add(EnvVarBuilder().withName("REPO_URL").withValue(url).build())
            workspace.branch?.let { add(EnvVarBuilder().withName("REPO_BRANCH").withValue(it).build()) }
        }
        // REPO_URLS carries the workspace's additional repos (everything
        // attached that is not the primary), as url#branch entries. The
        // entrypoint clones each into /workspace/<repo-name> over the
        // App-token credential helper.
        additionalRepoUrls(workspace).takeIf { it.isNotEmpty() }?.let { urls ->
            add(EnvVarBuilder().withName("REPO_URLS").withValue(urls.joinToString(" ")).build())
        }
    }

    private fun additionalRepoUrls(workspace: Workspace): List<String> {
        val links = workspaceRepos.ifAvailable ?: return emptyList()
        val repos = repositories.ifAvailable ?: return emptyList()
        return links
            .findAllByWorkspaceId(workspace.id)
            .filterNot { it.isPrimary }
            .mapNotNull { link ->
                repos.findById(link.repositoryId)?.let { repo -> "${repo.repoUrl}#${repo.defaultBranch}" }
            }
    }

    private fun dockerEnv(setup: RunnerSetupProvisioningSpec) =
        if (!setup.dockerSocketEnabled) {
            emptyList()
        } else {
            listOf(
                EnvVarBuilder().withName("DOCKER_HOST").withValue("unix://${setup.dockerSocketPath}").build(),
                EnvVarBuilder()
                    .withName("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE")
                    .withValue(setup.dockerSocketPath)
                    .build(),
                EnvVarBuilder()
                    .withName("AGENT_RUNNER_NODE_HOST_IP")
                    .withNewValueFrom()
                    .withNewFieldRef()
                    .withFieldPath("status.hostIP")
                    .endFieldRef()
                    .endValueFrom()
                    .build(),
                EnvVarBuilder()
                    .withName("TESTCONTAINERS_HOST_OVERRIDE")
                    .withValue("$(AGENT_RUNNER_NODE_HOST_IP)")
                    .build(),
            )
        }

    // KB_URL + KB_BEARER_TOKEN are the exact names the knowledge-system
    // install.sh hooks read; without the bearer every hook short-circuits
    // to a no-op and the knowledge.* MCP tools are unreachable.
    private fun knowledgeEnv(setup: RunnerSetupProvisioningSpec) =
        listOf(
            EnvVarBuilder().withName("KB_URL").withValue(setup.knowledgeBaseUrl).build(),
            EnvVarBuilder()
                .withName("KB_BEARER_TOKEN")
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(setup.knowledgeBearerSecret)
                .withKey(setup.knowledgeBearerSecretKey)
                .endSecretKeyRef()
                .endValueFrom()
                .build(),
        )

    // The bearer is an optional Secret ref: an absent github-app Secret
    // keeps the Pod starting and the `gh` wrapper degrades to a no-op.
    private fun githubAppTokenEnv() =
        listOf(
            EnvVarBuilder().withName("GITHUB_APP_TOKEN_URL").withValue(props.githubAppTokenUrl).build(),
            EnvVarBuilder()
                .withName("GITHUB_APP_TOKEN_BEARER")
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(props.githubAppBearerSecret)
                .withKey(props.githubAppBearerSecretKey)
                .withOptional(true)
                .endSecretKeyRef()
                .endValueFrom()
                .build(),
        )

    // CLAUDE_CODE_OAUTH_TOKEN from the portal-managed Secret. `claude
    // setup-token` produces this long-lived token, which Claude Code prefers
    // over the mounted credential files, so a fresh runner picks up the latest
    // portal sign-in. Optional ref: an absent Secret/key keeps the Pod starting
    // and the runner falls back to the credential PVC.
    private fun claudeOauthEnv() =
        listOf(
            EnvVarBuilder()
                .withName("CLAUDE_CODE_OAUTH_TOKEN")
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(props.claudeOauthSecret)
                .withKey(props.claudeOauthSecretKey)
                .withOptional(true)
                .endSecretKeyRef()
                .endValueFrom()
                .build(),
        )

    private fun podVolumeMounts(setup: RunnerSetupProvisioningSpec) =
        buildList {
            add(VolumeMountBuilder().withName("workspace").withMountPath("/workspace").build())
            add(VolumeMountBuilder().withName("claude-credentials").withMountPath("/home/agent/.claude").build())
            add(VolumeMountBuilder().withName("codex-credentials").withMountPath("/home/agent/.codex").build())
            if (setup.dockerSocketEnabled) {
                add(
                    VolumeMountBuilder()
                        .withName(DOCKER_SOCKET_VOLUME)
                        .withMountPath(setup.dockerSocketPath)
                        .build(),
                )
            }
            add(
                VolumeMountBuilder()
                    .withName("github-deploy-key")
                    .withMountPath("/var/run/secrets/agents/github-deploy-key")
                    .withReadOnly(true)
                    .build(),
            )
            // Declarative MCP server set; the entrypoint seeds it into
            // ~/.claude.json. Optional volume, so an absent ConfigMap
            // leaves the runner with no managed MCP servers.
            add(
                VolumeMountBuilder()
                    .withName("mcp-config")
                    .withMountPath(setup.mcpDir)
                    .withReadOnly(true)
                    .build(),
            )
        }

    private fun podVolumes(
        workspacePvc: String,
        deployKeySecret: String,
        setup: RunnerSetupProvisioningSpec,
    ) = buildList {
        add(pvcVolume("workspace", workspacePvc))
        add(pvcVolume("claude-credentials", setup.claudeCredentialsPvc))
        add(pvcVolume("codex-credentials", setup.codexCredentialsPvc))
        dockerSocketVolume(setup)?.let(::add)
        add(githubDeployKeyVolume(deployKeySecret))
        add(mcpConfigVolume(setup))
    }

    private fun pvcVolume(
        name: String,
        claim: String,
    ) = VolumeBuilder()
        .withName(name)
        .withNewPersistentVolumeClaim()
        .withClaimName(claim)
        .endPersistentVolumeClaim()
        .build()

    private fun dockerSocketVolume(setup: RunnerSetupProvisioningSpec): Volume? =
        if (!setup.dockerSocketEnabled) {
            null
        } else {
            VolumeBuilder()
                .withName(DOCKER_SOCKET_VOLUME)
                .withNewHostPath()
                .withPath(setup.dockerSocketPath)
                .withType("Socket")
                .endHostPath()
                .build()
        }

    private fun githubDeployKeyVolume(deployKeySecret: String): Volume =
        VolumeBuilder()
            .withName("github-deploy-key")
            .withNewSecret()
            .withSecretName(deployKeySecret)
            .endSecret()
            .build()

    private fun mcpConfigVolume(setup: RunnerSetupProvisioningSpec): Volume =
        VolumeBuilder()
            .withName("mcp-config")
            .withNewConfigMap()
            .withName(setup.mcpServersConfigMap)
            .withOptional(true)
            .endConfigMap()
            .build()

    private fun podSupplementalGroups(setup: RunnerSetupProvisioningSpec): List<Long> =
        if (setup.dockerSocketEnabled) {
            (listOf(RUN_AS_GID) + setup.dockerSocketSupplementalGroups).distinct()
        } else {
            listOf(RUN_AS_GID)
        }

    private fun service(
        name: String,
        podName: String,
        gatewayPort: Int,
    ): io.fabric8.kubernetes.api.model.Service =
        ServiceBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(props.namespace)
            .endMetadata()
            .withNewSpec()
            .withSelector<String, String>(mapOf("agent-runner/workspace-id" to podName.substringAfter("agent-runner-")))
            .addNewPort()
            .withName("gateway")
            .withPort(gatewayPort)
            .withNewTargetPort("gateway")
            .endPort()
            .endSpec()
            .build()

    private fun legacySetupSpec(): RunnerSetupProvisioningSpec =
        RunnerSetupProvisioningSpec(
            setupId = AgentSetupId.default(),
            setupVersion = AgentSetupVersion.initial(),
            setupHash = "runtime-default",
            image = props.image,
            imagePullPolicy = props.imagePullPolicy,
            serviceAccount = props.serviceAccount,
            gatewayPort = props.gatewayPort,
            claudeCredentialsPvc = props.claudeCredentialsPvc,
            codexCredentialsPvc = props.codexCredentialsPvc,
            githubDeployKeySecret = props.githubDeployKeySecret,
            knowledgeBaseUrl = props.knowledgeBaseUrl,
            knowledgeBearerSecret = props.knowledgeBearerSecret,
            knowledgeBearerSecretKey = props.knowledgeBearerSecretKey,
            mcpServersConfigMap = props.mcpServersConfigMap,
            mcpProfile = props.defaultMcpProfile,
            dockerSocketEnabled = props.dockerSocketEnabled,
            dockerSocketPath = props.dockerSocketPath,
            dockerSocketSupplementalGroups = props.dockerSocketSupplementalGroups,
            nodeSelector = props.nodeSelector,
        )

    companion object {
        // Pod security: non-root, with fsGroup so PVC mounts get the right owner.
        private const val RUN_AS_UID = 1000L
        private const val RUN_AS_GID = 1000L
        private const val FS_GROUP = 1000L
        private const val DOCKER_SOCKET_VOLUME = "docker-socket"

        // Resource sizing. One Pod hosts the gateway JVM, the agent CLIs
        // (Claude Code, Codex) and the workspace's own processes in a
        // single memory cgroup. At a 3Gi limit a real Claude session
        // alongside the JVM tripped the cgroup OOM killer (exit 137),
        // taking every tmux session in the Pod down at once. The gateway
        // heap is now capped small (see agent-runner entrypoint), and the
        // limit is raised to 10Gi so the CLIs and build tooling have real
        // headroom; the request reserves a baseline that covers the JVM
        // plus an idle CLI.
        private const val CPU_REQUEST = "250m"
        private const val MEMORY_REQUEST = "2Gi"
        private const val CPU_LIMIT = "2000m"
        private const val MEMORY_LIMIT = "10Gi"

        // Probe cadence. The startup probe loops 60×5s = 5 min so the
        // gateway's JVM cold start (slower on a fresh image pull)
        // completes before liveness can fire; readiness shares the same
        // budget as a backstop.
        private const val STARTUP_PERIOD_SECONDS = 5
        private const val STARTUP_FAILURE_THRESHOLD = 60
        private const val READINESS_PERIOD_SECONDS = 5
        private const val READINESS_FAILURE_THRESHOLD = 60
        private const val LIVENESS_PERIOD_SECONDS = 10
    }
}

/**
 * Pure helpers for agent-runner image-version handling, extracted so the
 * tag parsing and pinning logic is unit-testable without a Kubernetes cluster.
 */
internal object RunnerImageVersions {
    /**
     * The tag portion of an image reference such as
     * `ghcr.io/.../agent-runner:v0.12.0` → `v0.12.0`. Null when the reference
     * has no tag, is digest-pinned (`…@sha256:…`), or is blank.
     */
    fun tagOf(image: String?): String? {
        if (image.isNullOrBlank() || image.contains("@")) return null
        return image.substringAfterLast(":", "").takeIf { it.isNotBlank() && it != image }
    }

    /**
     * Pin a configured image reference to [version] so the running version is
     * an explicit fact in the Pod spec. Returns the reference unchanged when no
     * version is known (local/dev) or it is already digest-pinned.
     */
    fun pin(
        configured: String,
        version: String?,
    ): String {
        if (version.isNullOrBlank() || configured.contains("@")) return configured
        val repo = configured.substringBeforeLast(":", configured)
        return "$repo:$version"
    }
}

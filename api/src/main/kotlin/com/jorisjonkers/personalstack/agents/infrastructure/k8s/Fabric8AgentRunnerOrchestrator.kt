package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.AgentCredentialProvider
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.RunnerState
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.port.AgentCredentialRepository
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Probe
import io.fabric8.kubernetes.api.model.ProbeBuilder
import io.fabric8.kubernetes.api.model.Quantity
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
import java.util.concurrent.TimeUnit

/**
 * Owns the entire lifecycle of one workspace's runner Pod. The shape
 * is fixed: one Pod (`agent-runner-<short-id>`), one workspace PVC
 * (`workspace-<short-id>`), one ClusterIP Service so the gateway has
 * a stable in-cluster name. Adversarial use is anticipated, so the
 * Pod runs as UID 1000, with read-only mounts for per-workspace
 * credential Secrets that the agent itself doesn't need to write. Docker socket
 * access is intentionally host-equivalent and exists so repo tests
 * using Testcontainers can run inside future agent sessions.
 *
 * GitHub access uses the installed GitHub App: the runner mints
 * short-lived, repo-scoped installation tokens and authenticates over
 * HTTPS via a git credential helper, so repository access stays
 * scoped to repos the App is installed on.
 *
 * fabric8's fluent builder chains naturally split into one helper per
 * pod section (labels, env, mounts, volumes, container body); below
 * the 15-function / LargeClass detekt thresholds would defeat the
 * readability win. The class stays as a single orchestrator because
 * every helper here operates on the same shared props + client.
 */
@Suppress("TooManyFunctions", "LargeClass")
@Component
@Profile("!system-test")
class Fabric8AgentRunnerOrchestrator(
    private val client: KubernetesClient,
    private val props: AgentRuntimeProperties,
    private val credentialsProvider: ObjectProvider<AgentCredentialRepository>,
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
        val names =
            RunnerResourceNames(
                pod = "agent-runner-$short",
                pvc = "workspace-$short",
                service = "agent-runner-$short",
            )
        val resources =
            RunnerResources(
                workspace = workspace,
                setup = setup,
                runnerGeneration = runnerGeneration,
                names = names,
                credentialSecret = ensureCredentialSecret(workspace, short),
            )
        applyResources(resources)
        val endpoint = "http://${names.service}.${props.namespace}.svc.cluster.local:${setup.gatewayPort}"
        log.info(
            "provisioned runner pod {} for workspace {} using setup {}@{} generation {}",
            names.pod,
            workspace.id,
            setup.setupId,
            setup.setupVersion,
            runnerGeneration,
        )
        return AgentRunnerOrchestrator.RunnerHandle(
            podName = names.pod,
            pvcName = names.pvc,
            gatewayEndpoint = endpoint,
        )
    }

    private data class RunnerResourceNames(
        val pod: String,
        val pvc: String,
        val service: String,
    )

    private data class RunnerResources(
        val workspace: Workspace,
        val setup: RunnerSetupProvisioningSpec,
        val runnerGeneration: Long,
        val names: RunnerResourceNames,
        val credentialSecret: CredentialSecret?,
    )

    private fun applyResources(resources: RunnerResources) {
        client
            .persistentVolumeClaims()
            .inNamespace(props.namespace)
            .resource(pvc(resources.names.pvc))
            .serverSideApply()
        client
            .pods()
            .inNamespace(props.namespace)
            .resource(pod(resources))
            .serverSideApply()
        client
            .services()
            .inNamespace(props.namespace)
            .resource(service(resources.names.service, resources.names.pod, resources.setup.gatewayPort))
            .serverSideApply()
    }

    override fun scaleDown(workspace: Workspace) {
        val short = workspace.id.short()
        val podName = "agent-runner-$short"
        client
            .pods()
            .inNamespace(props.namespace)
            .withName(podName)
            .delete()
        client
            .services()
            .inNamespace(props.namespace)
            .withName(podName)
            .delete()
        // Block until the Pod is fully gone before returning. A reprovision
        // (restart / "Update runner") calls scaleDown then provision back to
        // back; recreating the same-named Pod while the old one is still
        // Terminating — and still holding the ReadWriteOnce workspace PVC —
        // left the new runner unschedulable and the reprovision failing. The
        // first-provision path has no old Pod, which is why it always worked.
        runCatching {
            client
                .pods()
                .inNamespace(props.namespace)
                .withName(podName)
                .waitUntilCondition({ it == null }, POD_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure {
            log.warn("runner pod {} still terminating after timeout: {}", podName, it.message)
        }
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
        client
            .secrets()
            .inNamespace(props.namespace)
            .withName(credentialSecretName(short))
            .delete()
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

    private fun credentialSecretName(short: String): String = "agent-runner-credentials-$short"

    private data class CredentialSecret(
        val name: String,
        val hasClaude: Boolean,
        val hasClaudeCredentialsJson: Boolean,
        val hasClaudeAccountJson: Boolean,
        val hasCodex: Boolean,
        val hasCodexConfig: Boolean,
    )

    private fun ensureCredentialSecret(
        workspace: Workspace,
        short: String,
    ): CredentialSecret? {
        val name = credentialSecretName(short)
        val data =
            credentialSecretData(workspace)
                ?: run {
                    client
                        .secrets()
                        .inNamespace(props.namespace)
                        .withName(name)
                        .delete()
                    return null
                }
        client
            .secrets()
            .inNamespace(props.namespace)
            .resource(
                SecretBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(props.namespace)
                    .withLabels<String, String>(
                        mapOf(
                            "app.kubernetes.io/part-of" to "agent-runner",
                            "agent-runner/workspace-id" to short,
                        ),
                    ).endMetadata()
                    .withType("Opaque")
                    .withData<String, String>(data)
                    .build(),
            ).serverSideApply()
        return CredentialSecret(
            name = name,
            hasClaude = data.containsKey("claude_oauth_token"),
            hasClaudeCredentialsJson = data.containsKey("claude_credentials_json"),
            hasClaudeAccountJson = data.containsKey("claude_account_json"),
            hasCodex = data.containsKey("codex_auth_json"),
            hasCodexConfig = data.containsKey("codex_config_toml"),
        )
    }

    private fun credentialSecretData(workspace: Workspace): Map<String, String>? {
        val owner = workspace.ownerUserId?.takeIf { it.isNotBlank() } ?: return null
        val store = credentialsProvider.ifAvailable ?: return null
        val data =
            buildMap {
                val claude = loadCredential(store, owner, AgentCredentialProvider.CLAUDE)
                claude?.payload?.get("oauth_token")?.takeIf { it.isNotBlank() }?.let {
                    put("claude_oauth_token", b64(it))
                }
                claude?.payload?.get("credentials_json")?.takeIf { it.isNotBlank() }?.let {
                    put("claude_credentials_json", b64(it))
                }
                claude?.payload?.get("account_json")?.takeIf { it.isNotBlank() }?.let {
                    put("claude_account_json", b64(it))
                }
                val codex = loadCredential(store, owner, AgentCredentialProvider.CODEX)
                val codexAuth = codex?.payload?.get("auth_json")?.takeIf { it.isNotBlank() }
                val codexConfig = codex?.payload?.get("config_toml")?.takeIf { it.isNotBlank() }
                if (codexAuth != null) {
                    put("codex_auth_json", b64(codexAuth))
                    // config_toml is optional; the runner self-provisions one when absent.
                    codexConfig?.let { put("codex_config_toml", b64(it)) }
                }
            }
        return data.takeIf { it.isNotEmpty() }
    }

    private fun loadCredential(
        store: AgentCredentialRepository,
        owner: String,
        provider: AgentCredentialProvider,
    ) = runCatching { store.find(owner, provider) }
        .onFailure { log.warn("could not load {} credential for workspace owner", provider) }
        .getOrNull()
        ?.takeUnless { it.valid == false }

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
    private fun pod(resources: RunnerResources): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName(resources.names.pod)
            .withNamespace(props.namespace)
            .withLabels<String, String>(podLabels(resources.workspace, resources.setup, resources.runnerGeneration))
            .withAnnotations<String, String>(podAnnotations(resources.setup))
            .endMetadata()
            .withNewSpec()
            .withServiceAccountName(resources.setup.serviceAccount)
            // The runner never calls the Kubernetes API itself — cluster
            // reads go through the read-only kubernetes MCP server over
            // HTTP. Don't project the SA token into the Pod, so the agent
            // (which runs unsandboxed) holds no API credential at all and
            // cannot reach the API server even if the SA were later granted
            // RBAC by mistake.
            .withAutomountServiceAccountToken(false)
            .withNodeSelector<String, String>(resources.setup.nodeSelector)
            .withRestartPolicy("Always")
            .withNewSecurityContext()
            .withRunAsUser(RUN_AS_UID)
            .withRunAsGroup(RUN_AS_GID)
            .withFsGroup(FS_GROUP)
            .withSupplementalGroups(podSupplementalGroups(resources.setup))
            .endSecurityContext()
            .withInitContainers(agentStateInitContainer(resources.setup))
            .addNewContainer()
            .withName("agent-runner")
            // Pin to the release agents-api itself is on so the running
            // version is a verifiable fact in the Pod spec, not :latest.
            .withImage(RunnerImageVersions.pin(resources.setup.image, ownReleaseVersion()))
            .withImagePullPolicy(resources.setup.imagePullPolicy)
            .withPorts(
                ContainerPortBuilder()
                    .withName("gateway")
                    .withContainerPort(resources.setup.gatewayPort)
                    .build(),
            ).withEnv(
                podEnv(
                    resources.workspace,
                    resources.setup,
                    resources.runnerGeneration,
                    resources.credentialSecret,
                ),
            ).withVolumeMounts(podVolumeMounts(resources.setup, resources.credentialSecret))
            // Startup probe gates liveness + readiness until the gateway's
            // JVM has finished its cold start. Without it the liveness probe
            // (failureThreshold 3 x 10s ~= 30s, no initial delay) killed the
            // booting Spring Boot gateway before it bound :8090, which
            // re-provisioned the runner in a loop and 503'd every
            // start-session. 60 x 5s = 5 min of boot headroom.
            .withStartupProbe(startupProbe())
            .withReadinessProbe(readinessProbe())
            .withLivenessProbe(livenessProbe())
            .withNewResources()
            .withRequests<String, Quantity>(mapOf("cpu" to Quantity(CPU_REQUEST), "memory" to Quantity(MEMORY_REQUEST)))
            .withLimits<String, Quantity>(mapOf("cpu" to Quantity(CPU_LIMIT), "memory" to Quantity(MEMORY_LIMIT)))
            .endResources()
            .endContainer()
            .withVolumes(podVolumes(resources.names.pvc, resources.credentialSecret, resources.setup))
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

    private fun httpProbe(
        path: String = "/healthz",
        port: String = "gateway",
        periodSeconds: Int? = null,
        failureThreshold: Int? = null,
    ): Probe =
        ProbeBuilder()
            .withNewHttpGet()
            .withPath(path)
            .withNewPort(port)
            .endHttpGet()
            .also { b -> periodSeconds?.let { b.withPeriodSeconds(it) } }
            .also { b -> failureThreshold?.let { b.withFailureThreshold(it) } }
            .build()

    private fun startupProbe(): Probe =
        httpProbe(periodSeconds = STARTUP_PERIOD_SECONDS, failureThreshold = STARTUP_FAILURE_THRESHOLD)

    private fun readinessProbe(): Probe =
        httpProbe(periodSeconds = READINESS_PERIOD_SECONDS, failureThreshold = READINESS_FAILURE_THRESHOLD)

    private fun livenessProbe(): Probe = httpProbe(periodSeconds = LIVENESS_PERIOD_SECONDS)

    private fun podEnv(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
        credentialSecret: CredentialSecret?,
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
        addAll(agentCredentialEnv(credentialSecret))
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

    private fun agentCredentialEnv(credentialSecret: CredentialSecret?) =
        if (credentialSecret == null) {
            emptyList()
        } else {
            buildList {
                if (credentialSecret.hasClaudeCredentialsJson) {
                    add(
                        EnvVarBuilder()
                            .withName("AGENT_CLAUDE_CREDENTIALS_FILE")
                            .withValue("$AGENT_CREDENTIALS_MOUNT/claude_credentials_json")
                            .build(),
                    )
                }
                if (credentialSecret.hasClaudeAccountJson) {
                    add(
                        EnvVarBuilder()
                            .withName("AGENT_CLAUDE_ACCOUNT_FILE")
                            .withValue("$AGENT_CREDENTIALS_MOUNT/claude_account_json")
                            .build(),
                    )
                }
                if (credentialSecret.hasClaude && !credentialSecret.hasClaudeCredentialsJson) {
                    add(
                        EnvVarBuilder()
                            .withName("CLAUDE_CODE_OAUTH_TOKEN")
                            .withNewValueFrom()
                            .withNewSecretKeyRef()
                            .withName(credentialSecret.name)
                            .withKey("claude_oauth_token")
                            .endSecretKeyRef()
                            .endValueFrom()
                            .build(),
                    )
                }
                if (credentialSecret.hasCodex) {
                    add(
                        EnvVarBuilder()
                            .withName("AGENT_CODEX_AUTH_JSON_FILE")
                            .withValue("$AGENT_CREDENTIALS_MOUNT/codex_auth_json")
                            .build(),
                    )
                }
                if (credentialSecret.hasCodexConfig) {
                    add(
                        EnvVarBuilder()
                            .withName("AGENT_CODEX_CONFIG_TOML_FILE")
                            .withValue("$AGENT_CREDENTIALS_MOUNT/codex_config_toml")
                            .build(),
                    )
                }
            }
        }

    private fun podVolumeMounts(
        setup: RunnerSetupProvisioningSpec,
        credentialSecret: CredentialSecret?,
    ) = buildList {
        add(VolumeMountBuilder().withName("workspace").withMountPath("/workspace").build())
        addAll(agentStateVolumeMounts())
        if (credentialSecret != null) {
            add(
                VolumeMountBuilder()
                    .withName(AGENT_CREDENTIALS_VOLUME)
                    .withMountPath(AGENT_CREDENTIALS_MOUNT)
                    .withReadOnly(true)
                    .build(),
            )
        }
        if (setup.dockerSocketEnabled) {
            add(
                VolumeMountBuilder()
                    .withName(DOCKER_SOCKET_VOLUME)
                    .withMountPath(setup.dockerSocketPath)
                    .build(),
            )
        }
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

    private fun agentStateInitContainer(setup: RunnerSetupProvisioningSpec) =
        ContainerBuilder()
            .withName("agent-state-init")
            .withImage(RunnerImageVersions.pin(setup.image, ownReleaseVersion()))
            .withImagePullPolicy(setup.imagePullPolicy)
            .withCommand("/bin/sh", "-c")
            .withArgs(
                "mkdir -p /workspace/.agent-state/claude/projects " +
                    "/workspace/.agent-state/claude/backups " +
                    "/workspace/.agent-state/claude/todos " +
                    "/workspace/.agent-state/claude/shell-snapshots " +
                    "/workspace/.agent-state/codex/session-homes && " +
                    "chown -R 1000:1000 /workspace/.agent-state",
            ).withVolumeMounts(VolumeMountBuilder().withName("workspace").withMountPath("/workspace").build())
            .withNewSecurityContext()
            .withRunAsUser(0L)
            .withRunAsGroup(0L)
            .endSecurityContext()
            .build()

    private fun agentStateVolumeMounts() =
        listOf(
            agentStateVolumeMount(
                mountPath = "/home/agent/.claude/projects",
                subPath = ".agent-state/claude/projects",
            ),
            agentStateVolumeMount(
                mountPath = "/home/agent/.claude/backups",
                subPath = ".agent-state/claude/backups",
            ),
            agentStateVolumeMount(
                mountPath = "/home/agent/.claude/todos",
                subPath = ".agent-state/claude/todos",
            ),
            agentStateVolumeMount(
                mountPath = "/home/agent/.claude/shell-snapshots",
                subPath = ".agent-state/claude/shell-snapshots",
            ),
            agentStateVolumeMount(
                mountPath = "/home/agent/.codex/session-homes",
                subPath = ".agent-state/codex/session-homes",
            ),
        )

    private fun agentStateVolumeMount(
        mountPath: String,
        subPath: String,
    ) = VolumeMountBuilder()
        .withName("workspace")
        .withMountPath(mountPath)
        .withSubPath(subPath)
        .build()

    private fun podVolumes(
        workspacePvc: String,
        credentialSecret: CredentialSecret?,
        setup: RunnerSetupProvisioningSpec,
    ) = buildList {
        add(pvcVolume("workspace", workspacePvc))
        credentialSecret?.let { add(agentCredentialsVolume(it.name)) }
        dockerSocketVolume(setup)?.let(::add)
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

    private fun agentCredentialsVolume(credentialSecret: String): Volume =
        VolumeBuilder()
            .withName(AGENT_CREDENTIALS_VOLUME)
            .withNewSecret()
            .withSecretName(credentialSecret)
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
        private const val AGENT_CREDENTIALS_VOLUME = "agent-credentials"
        private const val AGENT_CREDENTIALS_MOUNT = "/var/run/secrets/agents/credentials"

        // How long scaleDown waits for the old runner Pod to fully terminate
        // (and release the ReadWriteOnce workspace PVC) before returning, so a
        // back-to-back provision recreates it on a clean slate.
        private const val POD_TERMINATION_TIMEOUT_SECONDS = 90L

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

package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import com.jorisjonkers.personalstack.agents.infrastructure.k8s.RunnerCredentialSecretManager.CredentialSecret
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import org.springframework.beans.factory.ObjectProvider

/**
 * Builds Kubernetes resource specs (Pod, PVC, Service) for a workspace runner.
 * Extracted from Fabric8AgentRunnerOrchestrator to keep that class below the
 * TooManyFunctions and LargeClass thresholds.
 *
 * fabric8's fluent builder chains naturally split into one helper per pod section
 * (labels, env, mounts, volumes, container body); each helper operates on the
 * same shared props. The @Suppress("LongMethod") on pod() is intentional —
 * the intermediate fluent types are package-private and cannot be split further.
 */
internal class RunnerPodSpecBuilder(
    private val props: AgentRuntimeProperties,
    private val workspaceRepos: ObjectProvider<WorkspaceRepositoryRepository>,
    private val repositories: ObjectProvider<RepositoryRepository>,
    private val ownReleaseVersion: () -> String?,
) {
    fun pvc(name: String): PersistentVolumeClaim =
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
    fun pod(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
        names: RunnerResourceNames,
        credentialSecret: CredentialSecret?,
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName(names.pod)
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
            .withInitContainers(agentStateInitContainer(setup))
            .addNewContainer()
            .withName("agent-runner")
            // Pin to the release agents-api itself is on so the running
            // version is a verifiable fact in the Pod spec, not :latest.
            .withImage(RunnerImageVersions.pin(setup.image, ownReleaseVersion()))
            .withImagePullPolicy(setup.imagePullPolicy)
            .withPorts(
                ContainerPortBuilder()
                    .withName("gateway")
                    .withContainerPort(setup.gatewayPort)
                    .build(),
            ).withEnv(podEnv(workspace, setup, runnerGeneration, credentialSecret))
            .withVolumeMounts(podVolumeMounts(setup, credentialSecret))
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
            .withVolumes(podVolumes(names.pvc, credentialSecret, setup))
            .endSpec()
            .build()

    fun service(
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

    private companion object {
        const val RUN_AS_UID = 1000L
        const val RUN_AS_GID = 1000L
        const val FS_GROUP = 1000L
        const val DOCKER_SOCKET_VOLUME = "docker-socket"
        const val AGENT_CREDENTIALS_VOLUME = "agent-credentials"
        const val AGENT_CREDENTIALS_MOUNT = "/var/run/secrets/agents/credentials"

        // Probe cadence. The startup probe loops 60×5s = 5 min so the
        // gateway's JVM cold start (slower on a fresh image pull)
        // completes before liveness can fire; readiness shares the same
        // budget as a backstop.
        const val STARTUP_PERIOD_SECONDS = 5
        const val STARTUP_FAILURE_THRESHOLD = 60
        const val READINESS_PERIOD_SECONDS = 5
        const val READINESS_FAILURE_THRESHOLD = 60
        const val LIVENESS_PERIOD_SECONDS = 10

        // Resource sizing.
        const val CPU_REQUEST = "250m"
        const val MEMORY_REQUEST = "2Gi"
        const val CPU_LIMIT = "2000m"
        const val MEMORY_LIMIT = "10Gi"
    }
}

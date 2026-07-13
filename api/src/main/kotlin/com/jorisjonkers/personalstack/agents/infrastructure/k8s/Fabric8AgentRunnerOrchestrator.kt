package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.RunnerState
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.port.AgentCredentialRepository
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
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
 * Pod construction, credential management, and state-reading are delegated to
 * RunnerPodSpecBuilder, RunnerCredentialSecretManager, and RunnerStateReader.
 */
@Component
@Profile("!system-test")
class Fabric8AgentRunnerOrchestrator(
    private val client: KubernetesClient,
    private val props: AgentRuntimeProperties,
    credentialsProvider: ObjectProvider<AgentCredentialRepository>,
    workspaceRepos: ObjectProvider<WorkspaceRepositoryRepository>,
    repositories: ObjectProvider<RepositoryRepository>,
) : AgentRunnerOrchestrator {
    private val log = LoggerFactory.getLogger(Fabric8AgentRunnerOrchestrator::class.java)
    private val credentials = RunnerCredentialSecretManager(client, props, credentialsProvider)
    private val podSpec = RunnerPodSpecBuilder(props, workspaceRepos, repositories, ::ownReleaseVersion)
    private val stateReader = RunnerStateReader(::ownReleaseVersion)

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
        val credentialSecret = credentials.ensureCredentialSecret(workspace, short)
        applyResources(workspace, setup, runnerGeneration, names, credentialSecret)
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

    private fun applyResources(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
        names: RunnerResourceNames,
        credentialSecret: RunnerCredentialSecretManager.CredentialSecret?,
    ) {
        client
            .persistentVolumeClaims()
            .inNamespace(props.namespace)
            .resource(podSpec.pvc(names.pvc))
            .serverSideApply()
        client
            .pods()
            .inNamespace(props.namespace)
            .resource(podSpec.pod(workspace, setup, runnerGeneration, names, credentialSecret))
            .serverSideApply()
        client
            .services()
            .inNamespace(props.namespace)
            .resource(podSpec.service(names.service, names.pod, setup.gatewayPort))
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
            .withName(credentials.credentialSecretName(short))
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
        return stateReader.runnerState(pod)
    }

    override fun isRunnerImageStale(workspace: Workspace): Boolean {
        val current = runnerImageVersion(workspace) ?: return false
        val target = targetRunnerImageVersion() ?: return false
        return current != target
    }

    override fun runnerImageVersion(workspace: Workspace): String? = runnerState(workspace)?.runnerImageVersion

    override fun targetRunnerImageVersion(): String? = ownReleaseVersion()

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

    /**
     * The release version this agents-api process is running, baked into the
     * image as `SERVICE_VERSION` (= the release-please tag, e.g. "v0.12.0").
     * The whole suite is published in lockstep, so this is also the target
     * agent-runner version. Null on a local/dev build where it is unset or
     * "unknown", which disables upgrade detection rather than guessing.
     */
    private fun ownReleaseVersion(): String? =
        System.getenv("SERVICE_VERSION")?.takeIf { it.isNotBlank() && it != "unknown" }

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
        // How long scaleDown waits for the old runner Pod to fully terminate
        // (and release the ReadWriteOnce workspace PVC) before returning, so a
        // back-to-back provision recreates it on a clean slate.
        private const val POD_TERMINATION_TIMEOUT_SECONDS = 90L
    }
}

internal data class RunnerResourceNames(
    val pod: String,
    val pvc: String,
    val service: String,
)

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

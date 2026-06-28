package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupBindingRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.port.BindingPresence
import com.jorisjonkers.personalstack.agents.domain.port.DockerSocketPresence
import com.jorisjonkers.personalstack.agents.domain.port.NodeSelectorPresence
import com.jorisjonkers.personalstack.agents.domain.port.RunnerBindingInventory
import com.jorisjonkers.personalstack.agents.domain.port.RunnerBindingInventorySnapshot
import com.jorisjonkers.personalstack.agents.domain.port.SecretBindingPresence
import com.jorisjonkers.personalstack.agents.domain.port.SecretKeyPresence
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("!system-test")
class Fabric8RunnerBindingInventory(
    private val client: KubernetesClient,
) : RunnerBindingInventory {
    private val log = LoggerFactory.getLogger(Fabric8RunnerBindingInventory::class.java)

    @Suppress("LongMethod")
    override fun inspect(
        definition: AgentSetupDefinition,
        workspace: Workspace?,
    ): RunnerBindingInventorySnapshot {
        val namespace = definition.namespace
        val nodeSelectorSatisfiable = nodeSelectorSatisfiable(definition.nodeSelector)
        return RunnerBindingInventorySnapshot(
            serviceAccount =
                BindingPresence(
                    ref = ref("ServiceAccount", namespace, definition.serviceAccount),
                    exists = serviceAccountExists(namespace, definition.serviceAccount),
                ),
            persistentVolumeClaims =
                listOfNotNull(
                    definition.claudeCredentialsPvc,
                    definition.codexCredentialsPvc,
                    workspace?.pvcName,
                ).distinct()
                    .map { name ->
                        BindingPresence(
                            ref = ref("PersistentVolumeClaim", namespace, name),
                            exists = pvcExists(namespace, name),
                        )
                    },
            secrets =
                listOf(
                    secretPresence(namespace, definition.githubDeployKeySecret),
                    secretPresence(
                        namespace = namespace,
                        name = definition.knowledgeBearerSecret,
                        requiredKeys = listOf(definition.knowledgeBearerSecretKey),
                    ),
                ),
            configMaps =
                listOf(
                    BindingPresence(
                        ref = ref("ConfigMap", namespace, definition.mcpServersConfigMap),
                        exists = configMapExists(namespace, definition.mcpServersConfigMap),
                    ),
                ),
            nodeSelector =
                NodeSelectorPresence(
                    selector = definition.nodeSelector,
                    satisfiable = nodeSelectorSatisfiable,
                ),
            dockerSocket =
                DockerSocketPresence(
                    enabled = definition.dockerSocketEnabled,
                    path = definition.dockerSocketPath,
                    nodeSelectorSatisfiable = nodeSelectorSatisfiable,
                ),
        )
    }

    private fun secretPresence(
        namespace: String,
        name: String,
        requiredKeys: List<String> = emptyList(),
    ): SecretBindingPresence {
        val secret =
            client
                .secrets()
                .inNamespace(namespace)
                .withName(name)
                .get()
        return SecretBindingPresence(
            ref = ref("Secret", namespace, name),
            exists = secret != null,
            requiredKeys =
                requiredKeys.map { key ->
                    SecretKeyPresence(
                        ref = ref("Secret", namespace, name, key),
                        exists =
                            secret?.data?.containsKey(key) == true ||
                                secret?.stringData?.containsKey(key) == true,
                    )
                },
        )
    }

    private fun serviceAccountExists(
        namespace: String,
        name: String,
    ): Boolean =
        client
            .serviceAccounts()
            .inNamespace(namespace)
            .withName(name)
            .get() != null

    private fun pvcExists(
        namespace: String,
        name: String,
    ): Boolean =
        client
            .persistentVolumeClaims()
            .inNamespace(namespace)
            .withName(name)
            .get() != null

    private fun configMapExists(
        namespace: String,
        name: String,
    ): Boolean =
        client
            .configMaps()
            .inNamespace(namespace)
            .withName(name)
            .get() != null

    /**
     * Listing nodes is a cluster-scoped call, so it needs RBAC beyond
     * the namespaced runner-controller Role agents-api is granted for
     * Pod/PVC/Secret management. Node-selector satisfiability is only a
     * preflight hint for the setup-options / restart-preview UI — the
     * Kubernetes scheduler remains the real authority (an unschedulable
     * Pod simply stays Pending and is surfaced through the runner
     * availability path). A missing `nodes:list` permission (or any
     * transient API error) must therefore not propagate, because
     * [KubernetesExceptionHandler] would turn it into a 502 that takes
     * down session launch, restart, and setup-options entirely. Degrade
     * to "assume satisfiable" and log instead.
     */
    private fun nodeSelectorSatisfiable(selector: Map<String, String>): Boolean {
        if (selector.isEmpty()) return true
        return try {
            client
                .nodes()
                .withLabels(selector)
                .list()
                .items
                .isNotEmpty()
        } catch (ex: KubernetesClientException) {
            log.warn(
                "Could not list nodes for selector {} (code={}); assuming satisfiable. " +
                    "Grant agents-api cluster-scoped nodes:list for accurate validation.",
                selector,
                ex.code,
                ex,
            )
            true
        }
    }

    private fun ref(
        kind: String,
        namespace: String,
        name: String,
        key: String? = null,
    ): AgentSetupBindingRef =
        AgentSetupBindingRef(
            kind = kind,
            namespace = namespace,
            name = name,
            key = key,
        )
}

@Component
@Profile("system-test")
class StubRunnerBindingInventory : RunnerBindingInventory {
    @Suppress("LongMethod")
    override fun inspect(
        definition: AgentSetupDefinition,
        workspace: Workspace?,
    ): RunnerBindingInventorySnapshot =
        RunnerBindingInventorySnapshot(
            serviceAccount =
                BindingPresence(
                    ref("ServiceAccount", definition.namespace, definition.serviceAccount),
                    true,
                ),
            persistentVolumeClaims =
                listOfNotNull(
                    definition.claudeCredentialsPvc,
                    definition.codexCredentialsPvc,
                    workspace?.pvcName,
                ).distinct()
                    .map { name ->
                        BindingPresence(
                            ref("PersistentVolumeClaim", definition.namespace, name),
                            true,
                        )
                    },
            secrets =
                listOf(
                    SecretBindingPresence(
                        ref("Secret", definition.namespace, definition.githubDeployKeySecret),
                        true,
                    ),
                    SecretBindingPresence(
                        ref = ref("Secret", definition.namespace, definition.knowledgeBearerSecret),
                        exists = true,
                        requiredKeys =
                            listOf(
                                SecretKeyPresence(
                                    ref(
                                        "Secret",
                                        definition.namespace,
                                        definition.knowledgeBearerSecret,
                                        definition.knowledgeBearerSecretKey,
                                    ),
                                    true,
                                ),
                            ),
                    ),
                ),
            configMaps =
                listOf(
                    BindingPresence(
                        ref("ConfigMap", definition.namespace, definition.mcpServersConfigMap),
                        true,
                    ),
                ),
            nodeSelector = NodeSelectorPresence(definition.nodeSelector, true),
            dockerSocket =
                DockerSocketPresence(
                    definition.dockerSocketEnabled,
                    definition.dockerSocketPath,
                    true,
                ),
        )

    private fun ref(
        kind: String,
        namespace: String,
        name: String,
        key: String? = null,
    ): AgentSetupBindingRef =
        AgentSetupBindingRef(
            kind = kind,
            namespace = namespace,
            name = name,
            key = key,
        )
}

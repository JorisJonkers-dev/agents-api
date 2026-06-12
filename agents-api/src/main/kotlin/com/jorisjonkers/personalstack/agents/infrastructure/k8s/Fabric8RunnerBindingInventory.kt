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
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("!system-test")
class Fabric8RunnerBindingInventory(
    private val client: KubernetesClient,
) : RunnerBindingInventory {
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

    private fun nodeSelectorSatisfiable(selector: Map<String, String>): Boolean =
        selector.isEmpty() ||
            client
                .nodes()
                .withLabels(selector)
                .list()
                .items
                .isNotEmpty()

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

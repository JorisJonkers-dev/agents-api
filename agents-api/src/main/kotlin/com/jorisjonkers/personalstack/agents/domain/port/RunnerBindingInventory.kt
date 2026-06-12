package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupBindingRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.Workspace

interface RunnerBindingInventory {
    fun inspect(
        definition: AgentSetupDefinition,
        workspace: Workspace?,
    ): RunnerBindingInventorySnapshot
}

data class RunnerBindingInventorySnapshot(
    val serviceAccount: BindingPresence,
    val persistentVolumeClaims: List<BindingPresence>,
    val secrets: List<SecretBindingPresence>,
    val configMaps: List<BindingPresence>,
    val nodeSelector: NodeSelectorPresence,
    val dockerSocket: DockerSocketPresence,
)

data class BindingPresence(
    val ref: AgentSetupBindingRef,
    val exists: Boolean,
)

data class SecretBindingPresence(
    val ref: AgentSetupBindingRef,
    val exists: Boolean,
    val requiredKeys: List<SecretKeyPresence> = emptyList(),
)

data class SecretKeyPresence(
    val ref: AgentSetupBindingRef,
    val exists: Boolean,
)

data class NodeSelectorPresence(
    val selector: Map<String, String>,
    val satisfiable: Boolean,
)

data class DockerSocketPresence(
    val enabled: Boolean,
    val path: String,
    val nodeSelectorSatisfiable: Boolean,
)

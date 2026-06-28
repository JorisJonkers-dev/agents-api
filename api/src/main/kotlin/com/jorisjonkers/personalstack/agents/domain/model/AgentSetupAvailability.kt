package com.jorisjonkers.personalstack.agents.domain.model

import java.time.Instant

data class AgentSetupDefinition(
    val id: AgentSetupId,
    val version: AgentSetupVersion,
    val displayName: String,
    val description: String?,
    val namespace: String,
    val image: String,
    val imagePullPolicy: String,
    val serviceAccount: String,
    val gatewayPort: Int,
    val claudeCredentialsPvc: String,
    val codexCredentialsPvc: String,
    val githubDeployKeySecret: String,
    val cliTools: Map<String, String>,
    val knowledgeBaseUrl: String,
    val knowledgeBearerSecret: String,
    val knowledgeBearerSecretKey: String,
    val mcpServersConfigMap: String,
    val defaultMcpProfile: String,
    val connectorConfig: Map<String, String>,
    val toolProfiles: List<String>,
    val toolAllowlist: List<String>,
    val dockerSocketEnabled: Boolean,
    val dockerSocketPath: String,
    val dockerSocketSupplementalGroups: List<Long>,
    val nodeSelector: Map<String, String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AgentSetupAvailability(
    val id: AgentSetupId,
    val version: AgentSetupVersion,
    val selectable: Boolean,
    val defaultSelectable: Boolean,
    val unavailableReason: String?,
    val updatedAt: Instant,
)

data class AgentSetupCatalogEntry(
    val definition: AgentSetupDefinition,
    val availability: AgentSetupAvailability,
)

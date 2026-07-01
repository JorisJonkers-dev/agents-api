package com.jorisjonkers.personalstack.agents.domain.model

import java.security.MessageDigest
import java.util.Locale

data class RunnerSetupProvisioningSpec(
    val setupId: AgentSetupId,
    val setupVersion: AgentSetupVersion,
    val setupHash: String,
    val image: String,
    val imagePullPolicy: String,
    val serviceAccount: String,
    val gatewayPort: Int,
    val claudeCredentialsPvc: String,
    val codexCredentialsPvc: String,
    val githubDeployKeySecret: String,
    val knowledgeBaseUrl: String,
    val knowledgeBearerSecret: String,
    val knowledgeBearerSecretKey: String,
    val mcpServersConfigMap: String,
    val mcpProfile: String,
    val mcpDir: String = "/etc/agent-mcp",
    val claudeMcpServersFile: String? = null,
    val codexMcpServersFile: String? = null,
    val githubMcpToolsets: String? = null,
    val githubMcpExcludeTools: String? = null,
    val dockerSocketEnabled: Boolean,
    val dockerSocketPath: String,
    val dockerSocketSupplementalGroups: List<Long>,
    val nodeSelector: Map<String, String>,
) {
    init {
        require(setupHash.isNotBlank()) { "runner setup hash must not be blank" }
        require(gatewayPort > 0) { "runner gateway port must be positive" }
        require(mcpProfile.isNotBlank()) { "runner MCP profile must not be blank" }
        require(mcpDir.startsWith("/")) { "runner MCP dir must be an absolute path" }
        require(dockerSocketPath.startsWith("/")) { "runner Docker socket path must be an absolute path" }
        require(dockerSocketSupplementalGroups.all { it > 0L }) {
            "runner Docker socket supplemental groups must contain positive gids"
        }
    }

    fun identity(runnerGeneration: Long): RunnerState.Identity =
        RunnerState.Identity(
            setupId = setupId,
            setupVersion = setupVersion,
            setupHash = setupHash,
            runnerGeneration = runnerGeneration,
        )

    companion object {
        fun from(definition: AgentSetupDefinition): RunnerSetupProvisioningSpec =
            RunnerSetupProvisioningSpec(
                setupId = definition.id,
                setupVersion = definition.version,
                setupHash = definition.stableHash(),
                image = definition.image,
                imagePullPolicy = definition.imagePullPolicy,
                serviceAccount = definition.serviceAccount,
                gatewayPort = definition.gatewayPort,
                claudeCredentialsPvc = definition.claudeCredentialsPvc,
                codexCredentialsPvc = definition.codexCredentialsPvc,
                githubDeployKeySecret = definition.githubDeployKeySecret,
                knowledgeBaseUrl = definition.knowledgeBaseUrl,
                knowledgeBearerSecret = definition.knowledgeBearerSecret,
                knowledgeBearerSecretKey = definition.knowledgeBearerSecretKey,
                mcpServersConfigMap = definition.mcpServersConfigMap,
                mcpProfile = definition.defaultMcpProfile,
                claudeMcpServersFile = definition.connectorConfig["AGENT_MCP_SERVERS_FILE"],
                codexMcpServersFile = definition.connectorConfig["AGENT_CODEX_MCP_FILE"],
                githubMcpToolsets = definition.connectorConfig["GITHUB_MCP_TOOLSETS"],
                githubMcpExcludeTools = definition.connectorConfig["GITHUB_MCP_EXCLUDE_TOOLS"],
                dockerSocketEnabled = definition.dockerSocketEnabled,
                dockerSocketPath = definition.dockerSocketPath,
                dockerSocketSupplementalGroups = definition.dockerSocketSupplementalGroups,
                nodeSelector = definition.nodeSelector,
            )

        private fun AgentSetupDefinition.stableHash(): String {
            val parts =
                listOf(
                    id.value,
                    version.value.toString(),
                    image,
                    imagePullPolicy,
                    serviceAccount,
                    gatewayPort.toString(),
                    claudeCredentialsPvc,
                    codexCredentialsPvc,
                    githubDeployKeySecret,
                    cliTools.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" },
                    knowledgeBaseUrl,
                    knowledgeBearerSecret,
                    knowledgeBearerSecretKey,
                    mcpServersConfigMap,
                    defaultMcpProfile,
                    connectorConfig.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" },
                    toolProfiles.joinToString(","),
                    toolAllowlist.joinToString(","),
                    dockerSocketEnabled.toString(),
                    dockerSocketPath,
                    dockerSocketSupplementalGroups.joinToString(","),
                    nodeSelector.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" },
                )
            return sha256(parts.joinToString("\u001f")).take(HASH_PREFIX_LENGTH)
        }

        private fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
        }

        private const val HASH_PREFIX_LENGTH = 16
    }
}

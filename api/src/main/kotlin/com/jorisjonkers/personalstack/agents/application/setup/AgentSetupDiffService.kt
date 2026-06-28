package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDiff
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDiffChange
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import org.springframework.stereotype.Service

@Service
class AgentSetupDiffService {
    fun diff(
        from: AgentSetupDefinition,
        to: AgentSetupDefinition,
    ): AgentSetupDiff =
        AgentSetupDiff(
            from = AgentSetupRef(from.id, from.version),
            to = AgentSetupRef(to.id, to.version),
            changes = changes(from, to),
        )

    private fun changes(
        from: AgentSetupDefinition,
        to: AgentSetupDefinition,
    ): List<AgentSetupDiffChange> =
        buildList {
            // image is the headline change an operator restarts for, so surface it first.
            compare("image", from.image, to.image)
            compare("displayName", from.displayName, to.displayName)
            compare("description", from.description, to.description)
            compare("namespace", from.namespace, to.namespace)
            compare("imagePullPolicy", from.imagePullPolicy, to.imagePullPolicy)
            compare("serviceAccount", from.serviceAccount, to.serviceAccount)
            compare("gatewayPort", from.gatewayPort, to.gatewayPort)
            compare("claudeCredentialsPvc", from.claudeCredentialsPvc, to.claudeCredentialsPvc)
            compare("codexCredentialsPvc", from.codexCredentialsPvc, to.codexCredentialsPvc)
            compare("githubDeployKeySecret", from.githubDeployKeySecret, to.githubDeployKeySecret)
            compare("cliTools", from.cliTools, to.cliTools)
            compare("knowledgeBaseUrl", from.knowledgeBaseUrl, to.knowledgeBaseUrl)
            compare("knowledgeBearerSecret", from.knowledgeBearerSecret, to.knowledgeBearerSecret)
            compare("knowledgeBearerSecretKey", from.knowledgeBearerSecretKey, to.knowledgeBearerSecretKey)
            compare("mcpServersConfigMap", from.mcpServersConfigMap, to.mcpServersConfigMap)
            compare("defaultMcpProfile", from.defaultMcpProfile, to.defaultMcpProfile)
            compare("connectorConfig", redactMap(from.connectorConfig), redactMap(to.connectorConfig), redacted = true)
            compare("toolProfiles", from.toolProfiles, to.toolProfiles)
            compare("toolAllowlist", from.toolAllowlist, to.toolAllowlist)
            compare("dockerSocketEnabled", from.dockerSocketEnabled, to.dockerSocketEnabled)
            compare("dockerSocketPath", from.dockerSocketPath, to.dockerSocketPath)
            compare(
                "dockerSocketSupplementalGroups",
                from.dockerSocketSupplementalGroups,
                to.dockerSocketSupplementalGroups,
            )
            compare("nodeSelector", from.nodeSelector, to.nodeSelector)
        }

    private fun MutableList<AgentSetupDiffChange>.compare(
        field: String,
        from: Any?,
        to: Any?,
        redacted: Boolean = false,
    ) {
        if (from == to) return
        add(
            AgentSetupDiffChange(
                field = field,
                fromValue = printable(from),
                toValue = printable(to),
                redacted = redacted,
            ),
        )
    }

    private fun printable(value: Any?): String? = value?.toString()

    private fun redactMap(values: Map<String, String>): Map<String, String> =
        values.mapValues { (key, value) ->
            if (key.isSensitive()) REDACTED else value
        }

    private fun String.isSensitive(): Boolean =
        contains("secret", ignoreCase = true) ||
            contains("token", ignoreCase = true) ||
            contains("bearer", ignoreCase = true) ||
            contains("key", ignoreCase = true) ||
            contains("credential", ignoreCase = true)

    companion object {
        const val REDACTED = "[redacted]"
    }
}

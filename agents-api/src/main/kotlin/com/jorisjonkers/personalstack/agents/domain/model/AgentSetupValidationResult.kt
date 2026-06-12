package com.jorisjonkers.personalstack.agents.domain.model

data class AgentSetupValidationResult(
    val target: AgentSetupRef,
    val valid: Boolean,
    val issues: List<AgentSetupValidationIssue> = emptyList(),
    val warnings: List<AgentSetupValidationIssue> = emptyList(),
) {
    fun message(): String =
        issues
            .joinToString("; ") { "${it.code}: ${it.message}" }
            .ifBlank { "agent setup validation failed" }
}

data class AgentSetupValidationIssue(
    val code: AgentSetupValidationIssueCode,
    val message: String,
    val binding: AgentSetupBindingRef? = null,
)

data class AgentSetupBindingRef(
    val kind: String,
    val namespace: String,
    val name: String,
    val key: String? = null,
)

enum class AgentSetupValidationIssueCode {
    TARGET_NOT_FOUND,
    TARGET_NOT_SELECTABLE,
    TARGET_STALE,
    UNSUPPORTED_AGENT_KIND,
    UNSUPPORTED_WORKSPACE_KIND,
    REPOSITORY_NOT_ALLOWED,
    PROJECT_NOT_ALLOWED,
    REPOSITORY_NOT_FOUND,
    REPOSITORY_NOT_LINKED_TO_PROJECT,
    DEPLOY_KEY_MISSING,
    SERVICE_ACCOUNT_MISSING,
    PVC_MISSING,
    SECRET_MISSING,
    SECRET_KEY_MISSING,
    CONFIG_MAP_MISSING,
    NODE_SELECTOR_UNSATISFIED,
    DOCKER_SOCKET_UNSATISFIED,
}

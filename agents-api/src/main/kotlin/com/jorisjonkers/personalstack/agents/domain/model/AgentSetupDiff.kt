package com.jorisjonkers.personalstack.agents.domain.model

data class AgentSetupDiff(
    val from: AgentSetupRef,
    val to: AgentSetupRef,
    val changes: List<AgentSetupDiffChange>,
) {
    val hasChanges: Boolean get() = changes.isNotEmpty()
}

data class AgentSetupRef(
    val id: AgentSetupId,
    val version: AgentSetupVersion,
)

data class AgentSetupDiffChange(
    val field: String,
    val fromValue: String?,
    val toValue: String?,
    val redacted: Boolean = false,
)

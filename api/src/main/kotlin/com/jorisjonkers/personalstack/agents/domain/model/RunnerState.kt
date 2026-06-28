package com.jorisjonkers.personalstack.agents.domain.model

data class RunnerState(
    val podName: String,
    val workspaceId: String?,
    val setupId: AgentSetupId?,
    val setupVersion: AgentSetupVersion?,
    val setupHash: String?,
    val runnerGeneration: Long?,
    val phase: String?,
    val containerReady: Boolean,
    // The agent-runner release version this pod is running — the version tag its
    // image is pinned to at provision (e.g. "v0.12.0"), read back from the Pod
    // spec. Lets us tell a runner is behind a newer agent-runner release. Null
    // when the image carries no version tag (e.g. a dev `:latest` build).
    val runnerImageVersion: String? = null,
) {
    data class Identity(
        val setupId: AgentSetupId,
        val setupVersion: AgentSetupVersion,
        val setupHash: String,
        val runnerGeneration: Long,
    )

    val identity: Identity?
        get() {
            val id = setupId ?: return null
            val version = setupVersion ?: return null
            val hash = setupHash ?: return null
            val generation = runnerGeneration ?: return null
            return Identity(id, version, hash, generation)
        }

    fun matches(expected: Identity): Boolean = identity == expected

    fun matches(
        setupId: AgentSetupId,
        setupVersion: AgentSetupVersion,
        runnerGeneration: Long,
    ): Boolean =
        this.setupId == setupId &&
            this.setupVersion == setupVersion &&
            this.runnerGeneration == runnerGeneration

    companion object {
        const val LABEL_WORKSPACE_ID = "agent-runner/workspace-id"
        const val LABEL_SETUP_ID = "agent-runner/setup-id"
        const val LABEL_SETUP_VERSION = "agent-runner/setup-version"
        const val LABEL_RUNNER_GENERATION = "agent-runner/runner-generation"
        const val ANNOTATION_SETUP_HASH = "agent-runner/setup-hash"
    }
}

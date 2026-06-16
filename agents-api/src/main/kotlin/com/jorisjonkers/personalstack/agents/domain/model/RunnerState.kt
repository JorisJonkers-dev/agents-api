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
    // Release marker (the agents-api image digest in force when this pod was
    // provisioned) used to detect a runner left behind on an old image after a
    // new release. Null on pods provisioned before this was introduced.
    val imageMarker: String? = null,
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
        const val ANNOTATION_IMAGE_MARKER = "agent-runner/image-marker"
    }
}

package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerState
import io.fabric8.kubernetes.api.model.Pod

/**
 * Reads runner Pod state and maps it to the domain RunnerState model.
 * Extracted from Fabric8AgentRunnerOrchestrator to keep that class below
 * the TooManyFunctions and LargeClass thresholds.
 */
internal class RunnerStateReader(
    private val ownReleaseVersion: () -> String?,
) {
    fun runnerState(pod: Pod): RunnerState {
        val containerReady =
            pod.status
                ?.containerStatuses
                ?.firstOrNull()
                ?.ready ?: false
        val labels = pod.metadata?.labels.orEmpty()
        val annotations = pod.metadata?.annotations.orEmpty()
        return RunnerState(
            podName = pod.metadata?.name.orEmpty(),
            workspaceId = labels[RunnerState.LABEL_WORKSPACE_ID],
            setupId = labels[RunnerState.LABEL_SETUP_ID]?.let(::parseSetupId),
            setupVersion = labels[RunnerState.LABEL_SETUP_VERSION]?.let(::parseSetupVersion),
            setupHash = annotations[RunnerState.ANNOTATION_SETUP_HASH],
            runnerGeneration = labels[RunnerState.LABEL_RUNNER_GENERATION]?.toLongOrNull(),
            phase = pod.status?.phase,
            containerReady = containerReady,
            runnerImageVersion =
                RunnerImageVersions.tagOf(
                    pod.spec
                        ?.containers
                        ?.firstOrNull()
                        ?.image,
                ),
        )
    }

    private fun parseSetupId(value: String): AgentSetupId? = runCatching { AgentSetupId(value) }.getOrNull()

    private fun parseSetupVersion(value: String): AgentSetupVersion? =
        value.toLongOrNull()?.let { runCatching { AgentSetupVersion(it) }.getOrNull() }
}

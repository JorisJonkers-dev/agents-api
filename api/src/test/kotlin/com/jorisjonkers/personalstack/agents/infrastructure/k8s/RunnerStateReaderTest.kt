package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerState
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunnerStateReaderTest {
    private val reader = RunnerStateReader()

    @Test
    fun `runnerState maps ready pod with all labels to domain model`() {
        val pod =
            PodBuilder()
                .withMetadata(
                    ObjectMetaBuilder()
                        .withName("agent-runner-abc123")
                        .withLabels<String, String>(
                            mapOf(
                                RunnerState.LABEL_WORKSPACE_ID to "abc123",
                                RunnerState.LABEL_SETUP_ID to "default",
                                RunnerState.LABEL_SETUP_VERSION to "1",
                                RunnerState.LABEL_RUNNER_GENERATION to "3",
                            ),
                        ).withAnnotations<String, String>(
                            mapOf(RunnerState.ANNOTATION_SETUP_HASH to "sha-abc"),
                        ).build(),
                ).withSpec(
                    PodSpecBuilder()
                        .addNewContainer()
                        .withName("agent-runner")
                        .withImage("ghcr.io/example/agent-runner:v0.18.0")
                        .endContainer()
                        .build(),
                ).withStatus(
                    PodStatus().apply {
                        phase = "Running"
                        containerStatuses =
                            listOf(
                                ContainerStatusBuilder().withReady(true).build(),
                            )
                    },
                ).build()

        val state = reader.runnerState(pod)

        assertThat(state.podName).isEqualTo("agent-runner-abc123")
        assertThat(state.workspaceId).isEqualTo("abc123")
        assertThat(state.setupId).isEqualTo(AgentSetupId("default"))
        assertThat(state.setupVersion).isEqualTo(AgentSetupVersion(1))
        assertThat(state.setupHash).isEqualTo("sha-abc")
        assertThat(state.runnerGeneration).isEqualTo(3L)
        assertThat(state.phase).isEqualTo("Running")
        assertThat(state.containerReady).isTrue()
        assertThat(state.runnerImageVersion).isEqualTo("v0.18.0")
    }

    @Test
    fun `runnerState maps pod with missing container status as not ready`() {
        val pod =
            PodBuilder()
                .withMetadata(ObjectMetaBuilder().withName("agent-runner-def456").build())
                .withStatus(PodStatus().apply { phase = "Pending" })
                .build()

        val state = reader.runnerState(pod)

        assertThat(state.containerReady).isFalse()
        assertThat(state.phase).isEqualTo("Pending")
        assertThat(state.runnerImageVersion).isNull()
    }

    @Test
    fun `runnerState returns null setupId and setupVersion when labels are missing`() {
        val pod =
            PodBuilder()
                .withMetadata(ObjectMetaBuilder().withName("agent-runner-no-labels").build())
                .withStatus(PodStatus())
                .build()

        val state = reader.runnerState(pod)

        assertThat(state.setupId).isNull()
        assertThat(state.setupVersion).isNull()
        assertThat(state.runnerGeneration).isNull()
    }

    @Test
    fun `runnerState returns null setupVersion when label is not a valid long`() {
        val pod =
            PodBuilder()
                .withMetadata(
                    ObjectMetaBuilder()
                        .withName("agent-runner-bad-labels")
                        .withLabels<String, String>(
                            mapOf(RunnerState.LABEL_SETUP_VERSION to "not-a-number"),
                        ).build(),
                ).withStatus(PodStatus())
                .build()

        val state = reader.runnerState(pod)

        assertThat(state.setupVersion).isNull()
    }
}

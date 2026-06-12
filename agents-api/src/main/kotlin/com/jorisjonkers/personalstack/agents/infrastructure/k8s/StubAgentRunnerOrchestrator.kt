package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.RunnerState
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * No-op orchestrator activated by the `system-test` Spring profile.
 * The system-test docker-compose stack doesn't include a Kubernetes
 * cluster, so the real fabric8 orchestrator (`@Profile("!system-test")`)
 * cannot run there — the Playwright workspace-flow specs would all
 * fall over on the first PVC apply.
 *
 * The stub returns synthesised runner-handle metadata so the
 * agents-api's workspace lifecycle commands behave identically
 * end-to-end except for the actual Pod side-effects. `isReady`
 * preserves the historical "ready without a real Pod" behaviour
 * until a provisioned synthetic state exists; after that it checks
 * the recorded runner identity.
 *
 * Real-cluster behaviour is exercised by
 * `Fabric8AgentRunnerOrchestratorIntegrationTest` in the agents-api
 * `integrationTest` source set against a Testcontainers k3s.
 */
@Component
@Profile("system-test")
class StubAgentRunnerOrchestrator : AgentRunnerOrchestrator {
    private val log = LoggerFactory.getLogger(StubAgentRunnerOrchestrator::class.java)
    private val states = mutableMapOf<String, RunnerState>()

    override fun provision(workspace: Workspace): AgentRunnerOrchestrator.RunnerHandle =
        provision(
            workspace,
            RunnerSetupProvisioningSpec(
                setupId = AgentSetupId.default(),
                setupVersion = AgentSetupVersion.initial(),
                setupHash = "system-test-default",
                image = "system-test",
                imagePullPolicy = "IfNotPresent",
                serviceAccount = "system-test",
                gatewayPort = 8090,
                claudeCredentialsPvc = "claude-credentials",
                codexCredentialsPvc = "codex-credentials",
                githubDeployKeySecret = "agents-github-deploy-key",
                knowledgeBaseUrl = "http://stub.system-test.invalid:0",
                knowledgeBearerSecret = "agents-kb-bearer",
                knowledgeBearerSecretKey = "bearer",
                mcpServersConfigMap = "agents-mcp-servers",
                mcpProfile = "minimal",
                dockerSocketEnabled = false,
                dockerSocketPath = "/var/run/docker.sock",
                dockerSocketSupplementalGroups = emptyList(),
                nodeSelector = emptyMap(),
            ),
            workspace.runnerSetupGeneration,
        )

    override fun provision(
        workspace: Workspace,
        setup: RunnerSetupProvisioningSpec,
        runnerGeneration: Long,
    ): AgentRunnerOrchestrator.RunnerHandle {
        val short = workspace.id.short()
        val handle =
            AgentRunnerOrchestrator.RunnerHandle(
                podName = "agent-runner-$short",
                pvcName = "workspace-$short",
                gatewayEndpoint = "http://stub.system-test.invalid:0",
            )
        states[workspace.id.toString()] =
            RunnerState(
                podName = handle.podName,
                workspaceId = short,
                setupId = setup.setupId,
                setupVersion = setup.setupVersion,
                setupHash = setup.setupHash,
                runnerGeneration = runnerGeneration,
                phase = "Running",
                containerReady = true,
            )
        log.info(
            "stub orchestrator: provision({}) -> {} setup {}@{} generation {}",
            workspace.id,
            handle.podName,
            setup.setupId,
            setup.setupVersion,
            runnerGeneration,
        )
        return handle
    }

    override fun scaleDown(workspace: Workspace) {
        log.info("stub orchestrator: scaleDown({}) — no-op", workspace.id)
    }

    override fun destroy(workspace: Workspace) {
        states.remove(workspace.id.toString())
        log.info("stub orchestrator: destroy({}) — no-op", workspace.id)
    }

    override fun runnerState(workspace: Workspace): RunnerState? = states[workspace.id.toString()]

    override fun isReady(workspace: Workspace): Boolean =
        runnerState(workspace)?.matches(
            setupId = workspace.currentRunnerSetupId,
            setupVersion = workspace.currentRunnerSetupVersion,
            runnerGeneration = workspace.runnerSetupGeneration,
        ) ?: true

    override fun isReady(
        workspace: Workspace,
        expectedIdentity: RunnerState.Identity,
    ): Boolean = runnerState(workspace)?.matches(expectedIdentity) == true
}

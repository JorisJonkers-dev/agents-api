package com.jorisjonkers.personalstack.agents.infrastructure.k8s

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
 * end-to-end except for the actual Pod side-effects. `isReady` is
 * permanently true, which lets the UI flip a fresh workspace
 * straight to `READY` (rather than parking in `STARTING` while
 * polling a Pod that will never schedule).
 *
 * Real-cluster behaviour is exercised by
 * `Fabric8AgentRunnerOrchestratorIntegrationTest` in the agents-api
 * `integrationTest` source set against a Testcontainers k3s.
 */
@Component
@Profile("system-test")
class StubAgentRunnerOrchestrator : AgentRunnerOrchestrator {
    private val log = LoggerFactory.getLogger(StubAgentRunnerOrchestrator::class.java)

    override fun provision(workspace: Workspace): AgentRunnerOrchestrator.RunnerHandle {
        val short = workspace.id.short()
        val handle =
            AgentRunnerOrchestrator.RunnerHandle(
                podName = "agent-runner-$short",
                pvcName = "workspace-$short",
                gatewayEndpoint = "http://stub.system-test.invalid:0",
            )
        log.info("stub orchestrator: provision({}) -> {}", workspace.id, handle.podName)
        return handle
    }

    override fun scaleDown(workspace: Workspace) {
        log.info("stub orchestrator: scaleDown({}) — no-op", workspace.id)
    }

    override fun destroy(workspace: Workspace) {
        log.info("stub orchestrator: destroy({}) — no-op", workspace.id)
    }

    override fun isReady(workspace: Workspace): Boolean = true
}

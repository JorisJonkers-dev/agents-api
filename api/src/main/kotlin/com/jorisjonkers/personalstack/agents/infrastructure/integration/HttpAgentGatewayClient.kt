package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Thin REST adapter for the agent-gateway sidecar. Each method is
 * one HTTP call; the gateway is the sole authority over what those
 * verbs actually mean, so this client deliberately holds no logic of
 * its own beyond URI building and response mapping.
 *
 * One method per gateway endpoint keeps route parity visible in this adapter.
 * Headless job operations are delegated to HeadlessJobGateway to keep class size
 * within detekt thresholds.
 */
@Component
class HttpAgentGatewayClient(
    private val restClient: RestClient,
    private val telemetry: AgentsApiTelemetry = AgentsApiTelemetry.NOOP,
) : AgentGatewayClient {
    private val headless = HeadlessJobGateway(restClient, telemetry, ::endpoint)

    private data class GatewayAgentDto(
        val id: String,
        val kind: WorkspaceAgentKind,
        val cwd: String,
        val cliSessionId: String? = null,
        val stableSessionId: String? = null,
        val epoch: Long = 1,
        val continuation: ContinuationBody? = null,
    )

    private data class SpawnBody(
        val kind: WorkspaceAgentKind,
        val workspacePath: String? = null,
        val stableSessionId: String? = null,
        val epoch: Long? = null,
        val continuation: ContinuationBody? = null,
        val resumeCliSessionId: String? = null,
    )

    private data class SendBody(
        val input: String,
        val enter: Boolean,
    )

    private data class StageInputBody(
        val content: String,
        val name: String?,
    )

    private data class StagedInputDto(
        val path: String,
        val bytes: Long,
        val name: String,
    )

    private data class CaptureResponse(
        val text: String = "",
    )

    private data class CloneBody(
        val repoUrl: String,
        val branch: String? = null,
        val intoDir: String? = null,
    )

    private data class OpenPrBody(
        val repoDir: String,
        val title: String,
        val body: String,
        val base: String = "main",
    )

    private data class GitResult(
        val ok: Boolean,
        val output: String,
    )

    private data class AgentIdleDto(
        val idleMillis: Long? = null,
    )

    private fun endpoint(workspace: Workspace): String =
        workspace.gatewayEndpoint ?: error("workspace ${workspace.id} not yet provisioned with a gateway endpoint")

    override fun spawnAgent(request: AgentGatewayClient.SpawnAgentRequest): AgentGatewayClient.GatewayAgent {
        val dto =
            restClient
                .post()
                .uri("${endpoint(request.workspace)}/agents")
                .body(
                    SpawnBody(
                        kind = request.kind,
                        workspacePath = request.workspacePath,
                        stableSessionId = request.stableSessionId?.value?.toString(),
                        epoch = request.epoch,
                        continuation = request.continuation?.toBody(),
                        resumeCliSessionId = request.resumeCliSessionId,
                    ),
                ).retrieve()
                .body(GatewayAgentDto::class.java)
                ?: error("empty response from gateway")
        return AgentGatewayClient.GatewayAgent(
            id = dto.id,
            kind = dto.kind,
            cwd = dto.cwd,
            cliSessionId = dto.cliSessionId,
            stableSessionId = dto.stableSessionId,
            epoch = dto.epoch,
            continuation = dto.continuation?.toDomain(),
        )
    }

    override fun stopAgent(
        workspace: Workspace,
        gatewayAgentId: String,
    ) {
        restClient
            .delete()
            .uri("${endpoint(workspace)}/agents/$gatewayAgentId")
            .retrieve()
            // idempotent — ignore 404 from a gateway agent we've already stopped
            .onStatus(HttpStatusCode::is4xxClientError) { _, _ -> }
            .toBodilessEntity()
    }

    override fun cleanupStableSession(
        workspace: Workspace,
        stableSessionId: WorkspaceAgentSessionId,
    ) {
        restClient
            .delete()
            .uri("${endpoint(workspace)}/agents/transcripts/${stableSessionId.value}")
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError) { _, _ -> }
            .toBodilessEntity()
    }

    override fun sendInput(
        workspace: Workspace,
        gatewayAgentId: String,
        input: String,
        enter: Boolean,
    ) {
        restClient
            .post()
            .uri("${endpoint(workspace)}/agents/$gatewayAgentId/send")
            .body(SendBody(input, enter))
            .retrieve()
            .toBodilessEntity()
    }

    override fun stageInput(
        workspace: Workspace,
        gatewayAgentId: String,
        content: String,
        name: String?,
    ): AgentGatewayClient.StagedInput {
        val dto =
            restClient
                .post()
                .uri("${endpoint(workspace)}/agents/$gatewayAgentId/staged-inputs")
                .body(StageInputBody(content, name))
                .retrieve()
                .body(StagedInputDto::class.java)
                ?: error("empty staged input response from gateway")
        return AgentGatewayClient.StagedInput(path = dto.path, bytes = dto.bytes, name = dto.name)
    }

    override fun capture(
        workspace: Workspace,
        gatewayAgentId: String,
    ): String {
        val body =
            restClient
                .get()
                .uri("${endpoint(workspace)}/agents/$gatewayAgentId/capture")
                .retrieve()
                .body(CaptureResponse::class.java)
        return body?.text.orEmpty()
    }

    override fun clone(
        workspace: Workspace,
        repoUrl: String,
        branch: String?,
    ): String {
        val resp =
            restClient
                .post()
                .uri("${endpoint(workspace)}/git/clone")
                .body(CloneBody(repoUrl = repoUrl, branch = branch))
                .retrieve()
                .body(GitResult::class.java)
                ?: error("empty response from gateway")
        return resp.output
    }

    override fun openPr(
        workspace: Workspace,
        repoDir: String,
        title: String,
        body: String,
        base: String,
    ): String {
        val resp =
            restClient
                .post()
                .uri("${endpoint(workspace)}/git/open-pr")
                .body(OpenPrBody(repoDir = repoDir, title = title, body = body, base = base))
                .retrieve()
                .body(GitResult::class.java)
                ?: error("empty response from gateway")
        return resp.output
    }

    override fun isReady(workspace: Workspace): Boolean =
        runCatching {
            restClient
                .get()
                .uri("${endpoint(workspace)}/healthz")
                .retrieve()
                .toBodilessEntity()
            true
        }.getOrElse { false }

    override fun agentIdle(
        workspace: Workspace,
        gatewayAgentId: String,
    ): Duration? =
        runCatching {
            restClient
                .get()
                .uri("${endpoint(workspace)}/agents/$gatewayAgentId")
                .retrieve()
                .body(AgentIdleDto::class.java)
                ?.idleMillis
                ?.let(Duration::ofMillis)
        }.getOrNull()

    override fun startHeadlessJob(request: AgentGatewayClient.HeadlessJobRequest): AgentGatewayClient.HeadlessJob =
        headless.startHeadlessJob(request)

    override fun pollHeadlessJob(
        workspace: Workspace,
        headlessJobId: String,
    ): AgentGatewayClient.HeadlessJob = headless.pollHeadlessJob(workspace, headlessJobId)
}

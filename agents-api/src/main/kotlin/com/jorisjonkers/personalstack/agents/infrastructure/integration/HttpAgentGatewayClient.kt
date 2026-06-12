package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Thin REST adapter for the agent-gateway sidecar. Each method is
 * one HTTP call; the gateway is the sole authority over what those
 * verbs actually mean, so this client deliberately holds no logic of
 * its own beyond URI building and response mapping.
 *
 * One method per gateway endpoint keeps route parity visible in this adapter.
 */
@Suppress("TooManyFunctions")
@Component
class HttpAgentGatewayClient(
    private val restClient: RestClient,
    private val props: AgentRuntimeProperties,
) : AgentGatewayClient {
    private val log = LoggerFactory.getLogger(HttpAgentGatewayClient::class.java)

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
    )

    private data class ContinuationBody(
        val reason: String? = null,
        val previousEpoch: Long? = null,
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

    private data class VerifyBody(
        val repoUrl: String,
        val branch: String? = null,
    )

    private data class VerifyResult(
        val read: Boolean = false,
        val write: Boolean = false,
        val detail: String = "",
    )

    private fun endpoint(workspace: Workspace): String =
        workspace.gatewayEndpoint ?: error("workspace ${workspace.id} not yet provisioned with a gateway endpoint")

    override fun spawnAgent(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        workspacePath: String?,
        stableSessionId: WorkspaceAgentSessionId?,
        epoch: Long?,
        continuation: AgentGatewayClient.ContinuationMetadata?,
    ): AgentGatewayClient.GatewayAgent {
        val dto =
            restClient
                .post()
                .uri("${endpoint(workspace)}/agents")
                .body(
                    SpawnBody(
                        kind = kind,
                        workspacePath = workspacePath,
                        stableSessionId = stableSessionId?.value?.toString(),
                        epoch = epoch,
                        continuation = continuation?.toBody(),
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
            .uri("${endpoint(workspace)}/agents/stable-sessions/${stableSessionId.value}")
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
        @Suppress("UNCHECKED_CAST")
        val body =
            restClient
                .get()
                .uri("${endpoint(workspace)}/agents/$gatewayAgentId/capture")
                .retrieve()
                .body(Map::class.java) as Map<String, String>
        return body["text"] ?: ""
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

    override fun verifyAccess(
        repoUrl: String,
        branch: String?,
    ): AgentGatewayClient.AccessVerification? {
        val base = props.verifyGatewayBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            log.info("verifyAccess skipped — no verify-gateway-base-url configured")
            return null
        }
        return runCatching {
            restClient
                .post()
                .uri("$base/git/verify")
                .body(VerifyBody(repoUrl = repoUrl, branch = branch))
                .retrieve()
                .body(VerifyResult::class.java)
                ?: error("empty response from gateway /git/verify")
        }.map {
            AgentGatewayClient.AccessVerification(read = it.read, write = it.write, detail = it.detail)
        }.onFailure {
            log.warn("verifyAccess for {} failed: {}", repoUrl, it.message)
        }.getOrNull()
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

    private data class HeadlessRequestBody(
        val kind: WorkspaceAgentKind,
        val prompt: String,
        val cliSessionId: String? = null,
        val stableSessionId: String? = null,
        val epoch: Long? = null,
        val continuation: ContinuationBody? = null,
        val timeoutSeconds: Long? = null,
    )

    private data class HeadlessJobDto(
        val id: String,
        val status: String,
        val exitCode: Int? = null,
        val output: String? = null,
    )

    override fun startHeadlessJob(
        workspace: Workspace,
        kind: WorkspaceAgentKind,
        prompt: String,
        cliSessionId: String?,
        timeoutSeconds: Long?,
        stableSessionId: WorkspaceAgentSessionId?,
        epoch: Long?,
        continuation: AgentGatewayClient.ContinuationMetadata?,
    ): AgentGatewayClient.HeadlessJob {
        val dto =
            restClient
                .post()
                .uri("${endpoint(workspace)}/agents/headless")
                .body(
                    HeadlessRequestBody(
                        kind = kind,
                        prompt = prompt,
                        cliSessionId = cliSessionId,
                        stableSessionId = stableSessionId?.value?.toString(),
                        epoch = epoch,
                        continuation = continuation?.toBody(),
                        timeoutSeconds = timeoutSeconds,
                    ),
                ).retrieve()
                .body(HeadlessJobDto::class.java)
                ?: error("empty response from gateway /agents/headless")
        return dto.toDomain()
    }

    override fun pollHeadlessJob(
        workspace: Workspace,
        headlessJobId: String,
    ): AgentGatewayClient.HeadlessJob {
        val dto =
            restClient
                .get()
                .uri("${endpoint(workspace)}/agents/headless/$headlessJobId")
                .retrieve()
                .body(HeadlessJobDto::class.java)
                ?: error("empty response from gateway /agents/headless/$headlessJobId")
        return dto.toDomain()
    }

    private fun HeadlessJobDto.toDomain(): AgentGatewayClient.HeadlessJob =
        AgentGatewayClient.HeadlessJob(
            id = id,
            status =
                runCatching {
                    AgentGatewayClient.HeadlessStatus.valueOf(status)
                }.getOrDefault(AgentGatewayClient.HeadlessStatus.FAILED),
            exitCode = exitCode,
            output = output,
        )

    private fun AgentGatewayClient.ContinuationMetadata.toBody(): ContinuationBody =
        ContinuationBody(reason = reason, previousEpoch = previousEpoch)

    private fun ContinuationBody.toDomain(): AgentGatewayClient.ContinuationMetadata =
        AgentGatewayClient.ContinuationMetadata(reason = reason, previousEpoch = previousEpoch)
}

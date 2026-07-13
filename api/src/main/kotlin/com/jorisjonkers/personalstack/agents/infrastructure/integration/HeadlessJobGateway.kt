package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.ModeLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.Instant

/**
 * Handles headless job operations against the agent-gateway sidecar.
 * Extracted from HttpAgentGatewayClient to keep that class below the
 * TooManyFunctions and LargeClass thresholds.
 */
internal class HeadlessJobGateway(
    private val restClient: RestClient,
    private val telemetry: AgentsApiTelemetry,
    private val endpoint: (Workspace) -> String,
) {
    private data class HeadlessRequestBody(
        val kind: WorkspaceAgentKind,
        val prompt: String,
        val cliSessionId: String? = null,
        val stableSessionId: String? = null,
        val epoch: Long? = null,
        val continuation: ContinuationBody? = null,
        val timeoutSeconds: Long? = null,
        val enableKbHooks: Boolean = false,
        val partialMessages: Boolean = false,
    )

    internal data class HeadlessJobDto(
        val id: String,
        val status: String,
        val exitCode: Int? = null,
        val output: String? = null,
    )

    fun startHeadlessJob(request: AgentGatewayClient.HeadlessJobRequest): AgentGatewayClient.HeadlessJob {
        val dto =
            restClient
                .post()
                .uri("${endpoint(request.workspace)}/agents/headless")
                .body(
                    HeadlessRequestBody(
                        kind = request.kind,
                        prompt = request.prompt,
                        cliSessionId = request.cliSessionId,
                        stableSessionId = request.stableSessionId?.value?.toString(),
                        epoch = request.epoch,
                        continuation = request.continuation?.toBody(),
                        timeoutSeconds = request.timeoutSeconds,
                        enableKbHooks = request.enableKbHooks,
                        partialMessages = request.partialMessages,
                    ),
                ).retrieve()
                .body(HeadlessJobDto::class.java)
                ?: error("empty response from gateway /agents/headless")
        return dto.toDomain()
    }

    fun pollHeadlessJob(
        workspace: Workspace,
        headlessJobId: String,
    ): AgentGatewayClient.HeadlessJob {
        val startedAt = Instant.now()
        return observeJob(startedAt) {
            restClient
                .get()
                .uri("${endpoint(workspace)}/agents/headless/$headlessJobId")
                .retrieve()
                .body(HeadlessJobDto::class.java)
                ?: error("empty response from gateway /agents/headless/$headlessJobId")
        }
    }

    private fun observeJob(
        startedAt: Instant,
        fetch: () -> HeadlessJobDto,
    ): AgentGatewayClient.HeadlessJob =
        runCatching {
            val dto = fetch()
            Triple(dto.toDomain(), dto.toOutcomeLabel(), dto.toFailureReasonLabel())
        }.onSuccess { (_, outcome, reason) ->
            recordPoll(startedAt, outcome, reason)
        }.onFailure { ex ->
            recordPoll(startedAt, OutcomeLabel.FAILURE, gatewayFailureReason(ex))
        }.getOrThrow()
            .first

    private fun recordPoll(
        startedAt: Instant,
        outcome: OutcomeLabel,
        reason: FailureReasonLabel,
    ) {
        telemetry.recordOperation(
            OperationTelemetry(
                operation = OperationLabel.OTHER,
                mode = ModeLabel.HEADLESS,
                outcome = outcome,
                reason = reason,
                duration = Duration.between(startedAt, Instant.now()),
            ),
        )
    }

    private fun HeadlessJobDto.toDomain(): AgentGatewayClient.HeadlessJob =
        AgentGatewayClient.HeadlessJob(
            id = id,
            status = statusOrNull() ?: AgentGatewayClient.HeadlessStatus.FAILED,
            exitCode = exitCode,
            output = output,
        )

    private fun HeadlessJobDto.statusOrNull(): AgentGatewayClient.HeadlessStatus? =
        runCatching { AgentGatewayClient.HeadlessStatus.valueOf(status) }.getOrNull()

    private fun HeadlessJobDto.toOutcomeLabel(): OutcomeLabel =
        when (statusOrNull()) {
            AgentGatewayClient.HeadlessStatus.RUNNING -> OutcomeLabel.SUCCESS
            AgentGatewayClient.HeadlessStatus.COMPLETED -> OutcomeLabel.SUCCESS
            AgentGatewayClient.HeadlessStatus.FAILED -> OutcomeLabel.FAILURE
            AgentGatewayClient.HeadlessStatus.CANCELLED -> OutcomeLabel.CANCELLED
            null -> OutcomeLabel.FAILURE
        }

    private fun HeadlessJobDto.toFailureReasonLabel(): FailureReasonLabel =
        when (statusOrNull()) {
            AgentGatewayClient.HeadlessStatus.RUNNING -> FailureReasonLabel.NONE
            AgentGatewayClient.HeadlessStatus.COMPLETED -> FailureReasonLabel.NONE
            AgentGatewayClient.HeadlessStatus.FAILED -> FailureReasonLabel.PROCESS_EXITED
            AgentGatewayClient.HeadlessStatus.CANCELLED -> FailureReasonLabel.CANCELLED
            null -> FailureReasonLabel.fromRaw(status)
        }

    private fun gatewayFailureReason(ex: Throwable): FailureReasonLabel =
        when {
            ex is ResourceAccessException && FailureReasonLabel.fromRaw(ex.message) == FailureReasonLabel.TIMEOUT ->
                FailureReasonLabel.TIMEOUT
            ex is ResourceAccessException -> FailureReasonLabel.UPSTREAM_UNAVAILABLE
            ex is RestClientResponseException &&
                ex.statusCode.value() in setOf(HTTP_REQUEST_TIMEOUT, HTTP_GATEWAY_TIMEOUT) ->
                FailureReasonLabel.TIMEOUT
            ex is RestClientResponseException && ex.statusCode.is4xxClientError -> FailureReasonLabel.INVALID_REQUEST
            ex is RestClientResponseException && ex.statusCode.is5xxServerError ->
                FailureReasonLabel.UPSTREAM_UNAVAILABLE
            ex is IllegalStateException && ex.message?.contains("gateway endpoint") == true ->
                FailureReasonLabel.UPSTREAM_UNAVAILABLE
            else -> FailureReasonLabel.fromRaw(ex.message)
        }

    private companion object {
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_GATEWAY_TIMEOUT = 504
    }
}

internal data class ContinuationBody(
    val reason: String? = null,
    val previousEpoch: Long? = null,
    val fromSetupLabel: String? = null,
    val toSetupLabel: String? = null,
)

internal fun AgentGatewayClient.ContinuationMetadata.toBody(): ContinuationBody =
    ContinuationBody(
        reason = reason,
        previousEpoch = previousEpoch,
        fromSetupLabel = fromSetupLabel,
        toSetupLabel = toSetupLabel,
    )

internal fun ContinuationBody.toDomain(): AgentGatewayClient.ContinuationMetadata =
    AgentGatewayClient.ContinuationMetadata(
        reason = reason,
        previousEpoch = previousEpoch,
        fromSetupLabel = fromSetupLabel,
        toSetupLabel = toSetupLabel,
    )

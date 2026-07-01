package com.jorisjonkers.personalstack.agents.infrastructure.web.dto

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupBindingRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDiff
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDiffChange
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssue
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import java.time.Instant
import java.util.UUID

data class AgentSetupReferenceResponse(
    val id: String,
    val version: Long,
) {
    companion object {
        fun of(ref: AgentSetupRef): AgentSetupReferenceResponse =
            AgentSetupReferenceResponse(ref.id.value, ref.version.value)

        fun of(
            id: AgentSetupId,
            version: AgentSetupVersion,
        ): AgentSetupReferenceResponse = AgentSetupReferenceResponse(id.value, version.value)
    }
}

data class AgentSetupCatalogResponse(
    val setups: List<AgentSetupCatalogEntryResponse>,
)

data class AgentSetupCatalogEntryResponse(
    val setup: AgentSetupReferenceResponse,
    val displayName: String,
    val description: String?,
    val image: String,
    val cliTools: Map<String, String>,
    val toolProfiles: List<String>,
    val toolAllowlist: List<String>,
    val selectable: Boolean,
    val defaultSelectable: Boolean,
    val unavailableReason: String?,
    val updatedAt: Instant,
) {
    companion object {
        fun of(entry: AgentSetupCatalogEntry): AgentSetupCatalogEntryResponse =
            AgentSetupCatalogEntryResponse(
                setup = AgentSetupReferenceResponse.of(entry.definition.id, entry.definition.version),
                displayName = entry.definition.displayName,
                description = entry.definition.description,
                image = entry.definition.image,
                cliTools = entry.definition.cliTools,
                toolProfiles = entry.definition.toolProfiles,
                toolAllowlist = entry.definition.toolAllowlist,
                selectable = entry.availability.selectable,
                defaultSelectable = entry.availability.defaultSelectable,
                unavailableReason = entry.availability.unavailableReason,
                updatedAt = entry.availability.updatedAt,
            )
    }
}

data class AgentSetupDetailResponse(
    val setup: AgentSetupReferenceResponse,
    val displayName: String,
    val description: String?,
    val namespace: String,
    val image: String,
    val imagePullPolicy: String,
    val serviceAccount: String,
    val gatewayPort: Int,
    val claudeCredentialsPvc: String,
    val codexCredentialsPvc: String,
    val githubDeployKeySecret: String,
    val cliTools: Map<String, String>,
    val knowledgeBaseUrl: String,
    val knowledgeBearerSecret: String,
    val knowledgeBearerSecretKey: String,
    val mcpServersConfigMap: String,
    val defaultMcpProfile: String,
    val connectorConfig: Map<String, String>,
    val toolProfiles: List<String>,
    val toolAllowlist: List<String>,
    val dockerSocketEnabled: Boolean,
    val dockerSocketPath: String,
    val dockerSocketSupplementalGroups: List<Long>,
    val nodeSelector: Map<String, String>,
    val selectable: Boolean,
    val defaultSelectable: Boolean,
    val unavailableReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(entry: AgentSetupCatalogEntry): AgentSetupDetailResponse {
            val definition = entry.definition
            val availability = entry.availability
            return AgentSetupDetailResponse(
                setup = AgentSetupReferenceResponse.of(definition.id, definition.version),
                displayName = definition.displayName,
                description = definition.description,
                namespace = definition.namespace,
                image = definition.image,
                imagePullPolicy = definition.imagePullPolicy,
                serviceAccount = definition.serviceAccount,
                gatewayPort = definition.gatewayPort,
                claudeCredentialsPvc = definition.claudeCredentialsPvc,
                codexCredentialsPvc = definition.codexCredentialsPvc,
                githubDeployKeySecret = definition.githubDeployKeySecret,
                cliTools = definition.cliTools,
                knowledgeBaseUrl = definition.knowledgeBaseUrl,
                knowledgeBearerSecret = definition.knowledgeBearerSecret,
                knowledgeBearerSecretKey = definition.knowledgeBearerSecretKey,
                mcpServersConfigMap = definition.mcpServersConfigMap,
                defaultMcpProfile = definition.defaultMcpProfile,
                connectorConfig = definition.connectorConfig.redacted(),
                toolProfiles = definition.toolProfiles,
                toolAllowlist = definition.toolAllowlist,
                dockerSocketEnabled = definition.dockerSocketEnabled,
                dockerSocketPath = definition.dockerSocketPath,
                dockerSocketSupplementalGroups = definition.dockerSocketSupplementalGroups,
                nodeSelector = definition.nodeSelector,
                selectable = availability.selectable,
                defaultSelectable = availability.defaultSelectable,
                unavailableReason = availability.unavailableReason,
                createdAt = definition.createdAt,
                updatedAt = definition.updatedAt,
            )
        }
    }
}

data class AgentSetupDiffResponse(
    val from: AgentSetupReferenceResponse,
    val to: AgentSetupReferenceResponse,
    val hasChanges: Boolean,
    val changes: List<AgentSetupDiffChangeResponse>,
) {
    companion object {
        fun of(diff: AgentSetupDiff): AgentSetupDiffResponse =
            AgentSetupDiffResponse(
                from = AgentSetupReferenceResponse.of(diff.from),
                to = AgentSetupReferenceResponse.of(diff.to),
                hasChanges = diff.hasChanges,
                changes = diff.changes.map(AgentSetupDiffChangeResponse::of),
            )
    }
}

data class AgentSetupDiffChangeResponse(
    val field: String,
    val fromValue: String?,
    val toValue: String?,
    val redacted: Boolean,
) {
    companion object {
        fun of(change: AgentSetupDiffChange): AgentSetupDiffChangeResponse =
            AgentSetupDiffChangeResponse(
                field = change.field,
                fromValue = change.fromValue,
                toValue = change.toValue,
                redacted = change.redacted,
            )
    }
}

data class AgentSetupValidationResponse(
    val target: AgentSetupReferenceResponse,
    val valid: Boolean,
    val issues: List<AgentSetupValidationIssueResponse>,
    val warnings: List<AgentSetupValidationIssueResponse>,
) {
    companion object {
        fun of(result: AgentSetupValidationResult): AgentSetupValidationResponse =
            AgentSetupValidationResponse(
                target = AgentSetupReferenceResponse.of(result.target),
                valid = result.valid,
                issues = result.issues.map(AgentSetupValidationIssueResponse::of),
                warnings = result.warnings.map(AgentSetupValidationIssueResponse::of),
            )
    }
}

data class AgentSetupValidationIssueResponse(
    val code: String,
    val message: String,
    val binding: AgentSetupBindingResponse?,
) {
    companion object {
        fun of(issue: AgentSetupValidationIssue): AgentSetupValidationIssueResponse =
            AgentSetupValidationIssueResponse(
                code = issue.code.name,
                message = issue.message,
                binding = issue.binding?.let(AgentSetupBindingResponse::of),
            )
    }
}

data class AgentSetupBindingResponse(
    val kind: String,
    val namespace: String,
    val name: String,
    val key: String?,
) {
    companion object {
        fun of(binding: AgentSetupBindingRef): AgentSetupBindingResponse =
            AgentSetupBindingResponse(
                kind = binding.kind,
                namespace = binding.namespace,
                name = binding.name,
                key = binding.key,
            )
    }
}

data class SessionSetupStateResponse(
    val current: AgentSetupReferenceResponse,
    val pending: AgentSetupReferenceResponse?,
    val failed: FailedSessionSetupResponse?,
) {
    companion object {
        fun of(
            session: WorkspaceAgentSession,
            failed: SetupRestartEvent?,
        ): SessionSetupStateResponse =
            SessionSetupStateResponse(
                current = AgentSetupReferenceResponse.of(session.currentSetupId, session.currentSetupVersion),
                pending =
                    session.pendingSetupId?.let { id ->
                        AgentSetupReferenceResponse.of(id, requireNotNull(session.pendingSetupVersion))
                    },
                failed = failed?.let(FailedSessionSetupResponse::of),
            )
    }
}

data class FailedSessionSetupResponse(
    val setup: AgentSetupReferenceResponse,
    val reason: String?,
    val message: String?,
    val completedAt: Instant?,
) {
    companion object {
        fun of(event: SetupRestartEvent): FailedSessionSetupResponse =
            FailedSessionSetupResponse(
                setup = AgentSetupReferenceResponse.of(event.toSetupId, event.toSetupVersion),
                reason = event.reason,
                message = event.message,
                completedAt = event.completedAt,
            )
    }
}

data class SetupTargetOptionsResponse(
    val current: AgentSetupReferenceResponse,
    val pending: AgentSetupReferenceResponse?,
    val options: List<SetupTargetOptionResponse>,
)

data class SetupTargetOptionResponse(
    val setup: AgentSetupCatalogEntryResponse,
    val validation: AgentSetupValidationResponse,
)

data class SetupPreviewResponse(
    val current: AgentSetupReferenceResponse,
    val target: AgentSetupReferenceResponse,
    val diff: AgentSetupDiffResponse?,
    val validation: AgentSetupValidationResponse,
)

data class SetupTransitionHistoryResponse(
    val events: List<SetupTransitionResponse>,
)

data class SetupTransitionResponse(
    val id: UUID,
    val workspaceId: UUID,
    val sessionId: UUID?,
    val from: AgentSetupReferenceResponse?,
    val to: AgentSetupReferenceResponse,
    val status: SetupRestartEventStatus,
    val reason: String?,
    val message: String?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val updatedAt: Instant,
) {
    companion object {
        fun of(event: SetupRestartEvent): SetupTransitionResponse =
            SetupTransitionResponse(
                id = event.id,
                workspaceId = event.workspaceId.value,
                sessionId = event.sessionId?.value,
                from =
                    event.fromSetupId?.let { id ->
                        AgentSetupReferenceResponse.of(id, requireNotNull(event.fromSetupVersion))
                    },
                to = AgentSetupReferenceResponse.of(event.toSetupId, event.toSetupVersion),
                status = event.status,
                reason = event.reason,
                message = event.message,
                requestedAt = event.requestedAt,
                startedAt = event.startedAt,
                completedAt = event.completedAt,
                updatedAt = event.updatedAt,
            )
    }
}

private fun Map<String, String>.redacted(): Map<String, String> =
    mapValues { (key, value) -> if (key.isSensitive()) REDACTED else value }

private fun String.isSensitive(): Boolean =
    contains("secret", ignoreCase = true) ||
        contains("token", ignoreCase = true) ||
        contains("bearer", ignoreCase = true) ||
        contains("key", ignoreCase = true) ||
        contains("credential", ignoreCase = true)

private const val REDACTED = "[redacted]"

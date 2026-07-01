package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupBindingRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssue
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssueCode
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentSetupRepository
import com.jorisjonkers.personalstack.agents.domain.port.ProjectRepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.agents.domain.port.RunnerBindingInventory
import com.jorisjonkers.personalstack.agents.domain.port.RunnerBindingInventorySnapshot
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepositoryRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@Suppress("LongParameterList")
class AgentSetupValidationService(
    private val setups: AgentSetupRepository,
    private val bindingInventory: RunnerBindingInventory,
    private val repositories: RepositoryRepository,
    private val projectRepositories: ProjectRepositoryRepository,
    private val workspaceRepositories: WorkspaceRepositoryRepository,
) {
    fun validate(request: AgentSetupValidationInput): AgentSetupValidationResult {
        val ref = AgentSetupRef(request.targetId, request.targetVersion)
        val issues = mutableListOf<AgentSetupValidationIssue>()
        val warnings = mutableListOf<AgentSetupValidationIssue>()
        val target = resolveTarget(request.targetId, request.targetVersion, issues)
        if (target != null) {
            validateCompatibility(target, request, issues)
            validateRepositories(target, request, issues)
            validateBindings(bindingInventory.inspect(target.definition, request.workspace), issues)
            validateStaleSource(request, warnings)
        }
        return AgentSetupValidationResult(
            target = ref,
            valid = issues.isEmpty(),
            issues = issues,
            warnings = warnings,
        )
    }

    fun requireValid(request: AgentSetupValidationInput): AgentSetupValidationResult {
        val result = validate(request)
        if (!result.valid) throw AgentSetupValidationException(result)
        return result
    }

    private fun resolveTarget(
        id: AgentSetupId,
        version: AgentSetupVersion,
        issues: MutableList<AgentSetupValidationIssue>,
    ): AgentSetupCatalogEntry? {
        val definition = setups.findDefinition(id, version)
        val availability = setups.findAvailability(id, version)
        if (definition == null || availability == null) {
            issues.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.TARGET_NOT_FOUND,
                    message = "agent setup ${id.value}@${version.value} is not registered",
                ),
            )
            return null
        }
        if (!availability.selectable) {
            issues.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.TARGET_NOT_SELECTABLE,
                    message =
                        availability.unavailableReason
                            ?: "agent setup ${id.value}@${version.value} is not selectable",
                ),
            )
        }
        return AgentSetupCatalogEntry(definition = definition, availability = availability)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun validateCompatibility(
        target: AgentSetupCatalogEntry,
        request: AgentSetupValidationInput,
        issues: MutableList<AgentSetupValidationIssue>,
    ) {
        val config = target.definition.connectorConfig
        val agentKinds = config.enumSet<WorkspaceAgentKind>("supportedAgentKinds", "supported-agent-kinds")
        val requestedKind = request.agentKind ?: request.session?.kind
        if (requestedKind != null && agentKinds.isNotEmpty() && requestedKind !in agentKinds) {
            issues.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.UNSUPPORTED_AGENT_KIND,
                    message =
                        "agent setup ${target.definition.id.value}@${target.definition.version.value} " +
                            "does not support agent kind $requestedKind",
                ),
            )
        }
        val workspaceKinds = config.enumSet<WorkspaceKind>("supportedWorkspaceKinds", "supported-workspace-kinds")
        if (workspaceKinds.isNotEmpty() && request.workspace.kind !in workspaceKinds) {
            issues.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.UNSUPPORTED_WORKSPACE_KIND,
                    message =
                        "agent setup ${target.definition.id.value}@${target.definition.version.value} " +
                            "does not support workspace kind ${request.workspace.kind}",
                ),
            )
        }
        val allowedProjects = config.uuidSet("allowedProjectIds", "allowed-project-ids")
        val projectId = request.projectId ?: request.workspace.projectId
        if (projectId != null && allowedProjects.isNotEmpty() && projectId.value !in allowedProjects) {
            issues.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.PROJECT_NOT_ALLOWED,
                    message = "project ${projectId.value} is not allowed for setup ${target.definition.id.value}",
                ),
            )
        }
    }

    private fun validateRepositories(
        target: AgentSetupCatalogEntry,
        request: AgentSetupValidationInput,
        issues: MutableList<AgentSetupValidationIssue>,
    ) {
        val repositoryIds = request.repositoryIds.ifEmpty { repositoryIds(request.workspace) }
        val allowedRepositories =
            target.definition.connectorConfig.uuidSet("allowedRepositoryIds", "allowed-repository-ids")
        repositoryIds.forEach { repositoryId ->
            if (allowedRepositories.isNotEmpty() && repositoryId.value !in allowedRepositories) {
                issues.add(
                    AgentSetupValidationIssue(
                        code = AgentSetupValidationIssueCode.REPOSITORY_NOT_ALLOWED,
                        message =
                            "repository ${repositoryId.value} is not allowed for setup " +
                                target.definition.id.value,
                    ),
                )
            }
            val repository = repositories.findById(repositoryId)
            validateRepository(repositoryId, repository, request.projectId ?: request.workspace.projectId, issues)
        }
    }

    private fun validateRepository(
        repositoryId: RepositoryId,
        repository: Repository?,
        projectId: ProjectId?,
        issues: MutableList<AgentSetupValidationIssue>,
    ) {
        if (repository == null) {
            issues.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.REPOSITORY_NOT_FOUND,
                    message = "repository not found: repositoryId=${repositoryId.value}",
                ),
            )
            return
        }
        if (projectId != null && !projectRepositories.exists(projectId, repository.id)) {
            issues.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.REPOSITORY_NOT_LINKED_TO_PROJECT,
                    message = "repository ${repository.id.value} is not linked to project ${projectId.value}",
                ),
            )
        }
    }

    @Suppress("LongMethod")
    private fun validateBindings(
        snapshot: RunnerBindingInventorySnapshot,
        issues: MutableList<AgentSetupValidationIssue>,
    ) {
        if (!snapshot.serviceAccount.exists) {
            issues.add(bindingIssue(AgentSetupValidationIssueCode.SERVICE_ACCOUNT_MISSING, snapshot.serviceAccount.ref))
        }
        snapshot.persistentVolumeClaims
            .filterNot { it.exists }
            .forEach { issues.add(bindingIssue(AgentSetupValidationIssueCode.PVC_MISSING, it.ref)) }
        snapshot.secrets.forEach { secret ->
            if (!secret.exists) {
                issues.add(bindingIssue(AgentSetupValidationIssueCode.SECRET_MISSING, secret.ref))
            }
            secret.requiredKeys
                .filterNot { it.exists }
                .forEach { issues.add(bindingIssue(AgentSetupValidationIssueCode.SECRET_KEY_MISSING, it.ref)) }
        }
        snapshot.configMaps
            .filterNot { it.exists }
            .forEach { issues.add(bindingIssue(AgentSetupValidationIssueCode.CONFIG_MAP_MISSING, it.ref)) }
        if (!snapshot.nodeSelector.satisfiable) {
            issues.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.NODE_SELECTOR_UNSATISFIED,
                    message = "no Kubernetes node satisfies selector ${snapshot.nodeSelector.selector}",
                ),
            )
        }
        if (snapshot.dockerSocket.enabled && !snapshot.dockerSocket.nodeSelectorSatisfiable) {
            issues.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.DOCKER_SOCKET_UNSATISFIED,
                    message = "Docker socket ${snapshot.dockerSocket.path} requires a satisfiable node selector",
                ),
            )
        }
    }

    private fun validateStaleSource(
        request: AgentSetupValidationInput,
        warnings: MutableList<AgentSetupValidationIssue>,
    ) {
        val sourceId = request.session?.currentSetupId ?: request.workspace.currentRunnerSetupId
        val sourceVersion = request.session?.currentSetupVersion ?: request.workspace.currentRunnerSetupVersion
        if (setups.findDefinition(sourceId, sourceVersion) == null) {
            warnings.add(
                AgentSetupValidationIssue(
                    code = AgentSetupValidationIssueCode.TARGET_STALE,
                    message = "current setup ${sourceId.value}@${sourceVersion.value} is no longer registered",
                ),
            )
        }
    }

    private fun repositoryIds(workspace: Workspace): List<RepositoryId> =
        (
            listOfNotNull(workspace.repositoryId) +
                workspaceRepositories
                    .findAllByWorkspaceId(workspace.id)
                    .map { it.repositoryId }
        ).distinct()

    private fun bindingIssue(
        code: AgentSetupValidationIssueCode,
        ref: AgentSetupBindingRef,
    ): AgentSetupValidationIssue =
        AgentSetupValidationIssue(
            code = code,
            message = "${ref.kind} ${ref.namespace}/${ref.name}${ref.key?.let { ":$it" }.orEmpty()} is missing",
            binding = ref,
        )

    private inline fun <reified T : Enum<T>> Map<String, String>.enumSet(vararg keys: String): Set<T> =
        valueFor(*keys)
            ?.splitValues()
            .orEmpty()
            .mapNotNull { value -> enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } }
            .toSet()

    private fun Map<String, String>.uuidSet(vararg keys: String): Set<UUID> =
        valueFor(*keys)
            ?.splitValues()
            .orEmpty()
            .map { UUID.fromString(it) }
            .toSet()

    private fun Map<String, String>.valueFor(vararg keys: String): String? = keys.firstNotNullOfOrNull { this[it] }

    private fun String.splitValues(): List<String> =
        split(',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
}

data class AgentSetupValidationInput(
    val workspace: Workspace,
    val targetId: AgentSetupId,
    val targetVersion: AgentSetupVersion,
    val session: WorkspaceAgentSession? = null,
    val agentKind: WorkspaceAgentKind? = session?.kind,
    val projectId: ProjectId? = workspace.projectId,
    val repositoryIds: List<RepositoryId> = emptyList(),
)

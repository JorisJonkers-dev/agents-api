package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.port.AgentSetupRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqAgentSetupRepository(
    private val dsl: DSLContext,
    @param:Qualifier("agentsApiObjectMapper")
    private val objectMapper: ObjectMapper,
) : AgentSetupRepository {
    @Suppress("LongMethod")
    override fun saveDefinition(definition: AgentSetupDefinition): AgentSetupDefinition {
        val createdAt = definition.createdAt.atOffset(ZoneOffset.UTC)
        val updatedAt = definition.updatedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(DEFINITIONS)
            .set(SETUP_ID, definition.id.value)
            .set(SETUP_VERSION, definition.version.value)
            .set(DISPLAY_NAME, definition.displayName)
            .set(DESCRIPTION, definition.description)
            .set(NAMESPACE, definition.namespace)
            .set(IMAGE, definition.image)
            .set(IMAGE_PULL_POLICY, definition.imagePullPolicy)
            .set(SERVICE_ACCOUNT, definition.serviceAccount)
            .set(GATEWAY_PORT, definition.gatewayPort)
            .set(CLAUDE_CREDENTIALS_PVC, definition.claudeCredentialsPvc)
            .set(CODEX_CREDENTIALS_PVC, definition.codexCredentialsPvc)
            .set(GITHUB_DEPLOY_KEY_SECRET, definition.githubDeployKeySecret)
            .set(CLI_TOOLS, writeJson(definition.cliTools))
            .set(KNOWLEDGE_BASE_URL, definition.knowledgeBaseUrl)
            .set(KNOWLEDGE_BEARER_SECRET, definition.knowledgeBearerSecret)
            .set(KNOWLEDGE_BEARER_SECRET_KEY, definition.knowledgeBearerSecretKey)
            .set(MCP_SERVERS_CONFIG_MAP, definition.mcpServersConfigMap)
            .set(DEFAULT_MCP_PROFILE, definition.defaultMcpProfile)
            .set(CONNECTOR_CONFIG, writeJson(definition.connectorConfig))
            .set(TOOL_PROFILES, writeJson(definition.toolProfiles))
            .set(TOOL_ALLOWLIST, writeJson(definition.toolAllowlist))
            .set(DOCKER_SOCKET_ENABLED, definition.dockerSocketEnabled)
            .set(DOCKER_SOCKET_PATH, definition.dockerSocketPath)
            .set(DOCKER_SOCKET_SUPPLEMENTAL_GROUPS, writeJson(definition.dockerSocketSupplementalGroups))
            .set(NODE_SELECTOR, writeJson(definition.nodeSelector))
            .set(CREATED_AT, createdAt)
            .set(UPDATED_AT, updatedAt)
            .onConflict(SETUP_ID, SETUP_VERSION)
            .doUpdate()
            .set(DISPLAY_NAME, definition.displayName)
            .set(DESCRIPTION, definition.description)
            .set(NAMESPACE, definition.namespace)
            .set(IMAGE, definition.image)
            .set(IMAGE_PULL_POLICY, definition.imagePullPolicy)
            .set(SERVICE_ACCOUNT, definition.serviceAccount)
            .set(GATEWAY_PORT, definition.gatewayPort)
            .set(CLAUDE_CREDENTIALS_PVC, definition.claudeCredentialsPvc)
            .set(CODEX_CREDENTIALS_PVC, definition.codexCredentialsPvc)
            .set(GITHUB_DEPLOY_KEY_SECRET, definition.githubDeployKeySecret)
            .set(CLI_TOOLS, writeJson(definition.cliTools))
            .set(KNOWLEDGE_BASE_URL, definition.knowledgeBaseUrl)
            .set(KNOWLEDGE_BEARER_SECRET, definition.knowledgeBearerSecret)
            .set(KNOWLEDGE_BEARER_SECRET_KEY, definition.knowledgeBearerSecretKey)
            .set(MCP_SERVERS_CONFIG_MAP, definition.mcpServersConfigMap)
            .set(DEFAULT_MCP_PROFILE, definition.defaultMcpProfile)
            .set(CONNECTOR_CONFIG, writeJson(definition.connectorConfig))
            .set(TOOL_PROFILES, writeJson(definition.toolProfiles))
            .set(TOOL_ALLOWLIST, writeJson(definition.toolAllowlist))
            .set(DOCKER_SOCKET_ENABLED, definition.dockerSocketEnabled)
            .set(DOCKER_SOCKET_PATH, definition.dockerSocketPath)
            .set(DOCKER_SOCKET_SUPPLEMENTAL_GROUPS, writeJson(definition.dockerSocketSupplementalGroups))
            .set(NODE_SELECTOR, writeJson(definition.nodeSelector))
            .set(UPDATED_AT, updatedAt)
            .execute()
        return definition
    }

    override fun saveAvailability(availability: AgentSetupAvailability): AgentSetupAvailability {
        val updatedAt = availability.updatedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(AVAILABILITY)
            .set(SETUP_ID, availability.id.value)
            .set(SETUP_VERSION, availability.version.value)
            .set(SELECTABLE, availability.selectable)
            .set(DEFAULT_SELECTABLE, availability.defaultSelectable)
            .set(UNAVAILABLE_REASON, availability.unavailableReason)
            .set(UPDATED_AT, updatedAt)
            .onConflict(SETUP_ID, SETUP_VERSION)
            .doUpdate()
            .set(SELECTABLE, availability.selectable)
            .set(DEFAULT_SELECTABLE, availability.defaultSelectable)
            .set(UNAVAILABLE_REASON, availability.unavailableReason)
            .set(UPDATED_AT, updatedAt)
            .execute()
        return availability
    }

    override fun findDefinition(
        id: AgentSetupId,
        version: AgentSetupVersion,
    ): AgentSetupDefinition? =
        dsl
            .selectFrom(DEFINITIONS)
            .where(SETUP_ID.eq(id.value))
            .and(SETUP_VERSION.eq(version.value))
            .fetchOne()
            ?.toDefinition()

    override fun findAvailability(
        id: AgentSetupId,
        version: AgentSetupVersion,
    ): AgentSetupAvailability? =
        dsl
            .selectFrom(AVAILABILITY)
            .where(SETUP_ID.eq(id.value))
            .and(SETUP_VERSION.eq(version.value))
            .fetchOne()
            ?.toAvailability()

    override fun findSelectable(): List<AgentSetupCatalogEntry> =
        dsl
            .selectFrom(AVAILABILITY)
            .where(SELECTABLE.eq(true))
            .orderBy(DEFAULT_SELECTABLE.desc(), SETUP_ID.asc(), SETUP_VERSION.desc())
            .fetch()
            .mapNotNull { it.toCatalogEntry() }

    override fun findDefaultSelectable(): AgentSetupCatalogEntry? =
        dsl
            .selectFrom(AVAILABILITY)
            .where(SELECTABLE.eq(true))
            .and(DEFAULT_SELECTABLE.eq(true))
            .fetchOne()
            ?.toCatalogEntry()

    override fun backfillRuntimeDerivedSelections(
        setupId: AgentSetupId,
        version: AgentSetupVersion,
    ): Int {
        val workspaceCount =
            dsl
                .update(WORKSPACES)
                .set(WORKSPACE_CURRENT_SETUP_ID, setupId.value)
                .set(WORKSPACE_CURRENT_SETUP_VERSION, version.value)
                .set(
                    RUNNER_SETUP_GENERATION,
                    DSL.greatest(RUNNER_SETUP_GENERATION, DSL.inline(1L)),
                ).where(WORKSPACE_CURRENT_SETUP_ID.`in`(AgentSetupId.default().value, AgentSetupId.legacy().value))
                .and(
                    POD_NAME.isNotNull
                        .or(PVC_NAME.isNotNull)
                        .or(GATEWAY_ENDPOINT.isNotNull),
                ).execute()
        val sessionCount =
            dsl
                .update(WAS)
                .set(WAS_CURRENT_SETUP_ID, setupId.value)
                .set(WAS_CURRENT_SETUP_VERSION, version.value)
                .where(WAS_CURRENT_SETUP_ID.`in`(AgentSetupId.default().value, AgentSetupId.legacy().value))
                .and(GATEWAY_AGENT_ID.isNotNull)
                .execute()
        return workspaceCount + sessionCount
    }

    private fun Record.toCatalogEntry(): AgentSetupCatalogEntry? {
        val availability = toAvailability()
        val definition = findDefinition(availability.id, availability.version) ?: return null
        return AgentSetupCatalogEntry(definition = definition, availability = availability)
    }

    private fun Record.toDefinition(prefix: String? = null): AgentSetupDefinition =
        AgentSetupDefinition(
            id = AgentSetupId(this[field(prefix, "setup_id", String::class.java)]),
            version = AgentSetupVersion(this[field(prefix, "setup_version", Long::class.javaObjectType)]),
            displayName = this[field(prefix, "display_name", String::class.java)],
            description = this[field(prefix, "description", String::class.java)],
            namespace = this[field(prefix, "namespace", String::class.java)],
            image = this[field(prefix, "image", String::class.java)],
            imagePullPolicy = this[field(prefix, "image_pull_policy", String::class.java)],
            serviceAccount = this[field(prefix, "service_account", String::class.java)],
            gatewayPort = this[field(prefix, "gateway_port", Int::class.javaObjectType)],
            claudeCredentialsPvc = this[field(prefix, "claude_credentials_pvc", String::class.java)],
            codexCredentialsPvc = this[field(prefix, "codex_credentials_pvc", String::class.java)],
            githubDeployKeySecret = this[field(prefix, "github_deploy_key_secret", String::class.java)],
            cliTools = readMap(this[field(prefix, "cli_tools", String::class.java)]),
            knowledgeBaseUrl = this[field(prefix, "knowledge_base_url", String::class.java)],
            knowledgeBearerSecret = this[field(prefix, "knowledge_bearer_secret", String::class.java)],
            knowledgeBearerSecretKey = this[field(prefix, "knowledge_bearer_secret_key", String::class.java)],
            mcpServersConfigMap = this[field(prefix, "mcp_servers_config_map", String::class.java)],
            defaultMcpProfile = this[field(prefix, "default_mcp_profile", String::class.java)],
            connectorConfig = readMap(this[field(prefix, "connector_config", String::class.java)]),
            toolProfiles = readStringList(this[field(prefix, "tool_profiles", String::class.java)]),
            toolAllowlist = readStringList(this[field(prefix, "tool_allowlist", String::class.java)]),
            dockerSocketEnabled = this[field(prefix, "docker_socket_enabled", Boolean::class.javaObjectType)],
            dockerSocketPath = this[field(prefix, "docker_socket_path", String::class.java)],
            dockerSocketSupplementalGroups =
                readLongList(this[field(prefix, "docker_socket_supplemental_groups", String::class.java)]),
            nodeSelector = readMap(this[field(prefix, "node_selector", String::class.java)]),
            createdAt = this[field(prefix, "created_at", OffsetDateTime::class.java)].toInstant(),
            updatedAt = this[field(prefix, "updated_at", OffsetDateTime::class.java)].toInstant(),
        )

    private fun Record.toAvailability(prefix: String? = null): AgentSetupAvailability =
        AgentSetupAvailability(
            id = AgentSetupId(this[field(prefix, "setup_id", String::class.java)]),
            version = AgentSetupVersion(this[field(prefix, "setup_version", Long::class.javaObjectType)]),
            selectable = this[field(prefix, "selectable", Boolean::class.javaObjectType)],
            defaultSelectable = this[field(prefix, "default_selectable", Boolean::class.javaObjectType)],
            unavailableReason = this[field(prefix, "unavailable_reason", String::class.java)],
            updatedAt = this[field(prefix, "updated_at", OffsetDateTime::class.java)].toInstant(),
        )

    private fun <T> field(
        prefix: String?,
        name: String,
        type: Class<T>,
    ) = if (prefix == null) {
        DSL.field(name, type)
    } else {
        DSL.field("$prefix.$name", type)
    }

    private fun writeJson(value: Any): String = objectMapper.writeValueAsString(value)

    private fun readMap(value: String): Map<String, String> =
        objectMapper.readValue(value, object : TypeReference<Map<String, String>>() {})

    private fun readStringList(value: String): List<String> =
        objectMapper.readValue(value, object : TypeReference<List<String>>() {})

    private fun readLongList(value: String): List<Long> =
        objectMapper.readValue(value, object : TypeReference<List<Long>>() {})

    companion object {
        @JvmStatic val DEFINITIONS = DSL.table("agent_setup_definitions")

        @JvmStatic val AVAILABILITY = DSL.table("agent_setup_availability")

        @JvmStatic val WORKSPACES = DSL.table("workspaces")

        @JvmStatic val WAS = DSL.table("workspace_agent_sessions")

        @JvmStatic val SETUP_ID = DSL.field("setup_id", String::class.java)

        @JvmStatic val SETUP_VERSION = DSL.field("setup_version", Long::class.javaObjectType)

        @JvmStatic val DISPLAY_NAME = DSL.field("display_name", String::class.java)

        @JvmStatic val DESCRIPTION = DSL.field("description", String::class.java)

        @JvmStatic val NAMESPACE = DSL.field("namespace", String::class.java)

        @JvmStatic val IMAGE = DSL.field("image", String::class.java)

        @JvmStatic val IMAGE_PULL_POLICY = DSL.field("image_pull_policy", String::class.java)

        @JvmStatic val SERVICE_ACCOUNT = DSL.field("service_account", String::class.java)

        @JvmStatic val GATEWAY_PORT = DSL.field("gateway_port", Int::class.javaObjectType)

        @JvmStatic val CLAUDE_CREDENTIALS_PVC = DSL.field("claude_credentials_pvc", String::class.java)

        @JvmStatic val CODEX_CREDENTIALS_PVC = DSL.field("codex_credentials_pvc", String::class.java)

        @JvmStatic val GITHUB_DEPLOY_KEY_SECRET = DSL.field("github_deploy_key_secret", String::class.java)

        @JvmStatic val CLI_TOOLS = DSL.field("cli_tools", String::class.java)

        @JvmStatic val KNOWLEDGE_BASE_URL = DSL.field("knowledge_base_url", String::class.java)

        @JvmStatic val KNOWLEDGE_BEARER_SECRET = DSL.field("knowledge_bearer_secret", String::class.java)

        @JvmStatic val KNOWLEDGE_BEARER_SECRET_KEY = DSL.field("knowledge_bearer_secret_key", String::class.java)

        @JvmStatic val MCP_SERVERS_CONFIG_MAP = DSL.field("mcp_servers_config_map", String::class.java)

        @JvmStatic val DEFAULT_MCP_PROFILE = DSL.field("default_mcp_profile", String::class.java)

        @JvmStatic val CONNECTOR_CONFIG = DSL.field("connector_config", String::class.java)

        @JvmStatic val TOOL_PROFILES = DSL.field("tool_profiles", String::class.java)

        @JvmStatic val TOOL_ALLOWLIST = DSL.field("tool_allowlist", String::class.java)

        @JvmStatic val DOCKER_SOCKET_ENABLED = DSL.field("docker_socket_enabled", Boolean::class.javaObjectType)

        @JvmStatic val DOCKER_SOCKET_PATH = DSL.field("docker_socket_path", String::class.java)

        @JvmStatic val DOCKER_SOCKET_SUPPLEMENTAL_GROUPS =
            DSL.field("docker_socket_supplemental_groups", String::class.java)

        @JvmStatic val NODE_SELECTOR = DSL.field("node_selector", String::class.java)

        @JvmStatic val SELECTABLE = DSL.field("selectable", Boolean::class.javaObjectType)

        @JvmStatic val DEFAULT_SELECTABLE = DSL.field("default_selectable", Boolean::class.javaObjectType)

        @JvmStatic val UNAVAILABLE_REASON = DSL.field("unavailable_reason", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)

        @JvmStatic val WORKSPACE_CURRENT_SETUP_ID = DSL.field("current_runner_setup_id", String::class.java)

        @JvmStatic val WORKSPACE_CURRENT_SETUP_VERSION =
            DSL.field("current_runner_setup_version", Long::class.javaObjectType)

        @JvmStatic val RUNNER_SETUP_GENERATION =
            DSL.field("runner_setup_generation", Long::class.javaObjectType)

        @JvmStatic val POD_NAME = DSL.field("pod_name", String::class.java)

        @JvmStatic val PVC_NAME = DSL.field("pvc_name", String::class.java)

        @JvmStatic val GATEWAY_ENDPOINT = DSL.field("gateway_endpoint", String::class.java)

        @JvmStatic val WAS_CURRENT_SETUP_ID = DSL.field("current_setup_id", String::class.java)

        @JvmStatic val WAS_CURRENT_SETUP_VERSION =
            DSL.field("current_setup_version", Long::class.javaObjectType)

        @JvmStatic val GATEWAY_AGENT_ID = DSL.field("gateway_agent_id", String::class.java)
    }
}

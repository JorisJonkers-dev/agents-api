package com.jorisjonkers.personalstack.agents.config

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant

private const val NIXOS_DOCKER_GROUP_GID = 131L

private val DEFAULT_DOCKER_SOCKET_SUPPLEMENTAL_GROUPS =
    listOf(NIXOS_DOCKER_GROUP_GID)

private val DEFAULT_NODE_SELECTOR =
    mapOf(
        "personal-stack/node" to "enschede-gtx-960m-1",
        "personal-stack/capability-docker-socket" to "true",
    )

/**
 * Properties that drive Pod creation for workspaces. All sourced from
 * application.yml so the manifests need no rebuild when the image
 * tag or namespace moves.
 */
@ConfigurationProperties(prefix = "agent-runtime")
data class AgentRuntimeProperties(
    val namespace: String,
    val image: String,
    val imagePullPolicy: String = "Always",
    val serviceAccount: String,
    val workspaceStorageClass: String = "local-path",
    val workspaceStorageSize: String = "8Gi",
    val gatewayPort: Int = 8090,
    val claudeCredentialsPvc: String,
    val codexCredentialsPvc: String,
    val githubDeployKeySecret: String,
    // Base URL of the knowledge-api MCP server the installed Claude
    // Code hooks read from `KB_URL` (they append `/mcp` themselves).
    // The in-cluster address skips edge + forward-auth, which a CLI
    // can't satisfy.
    val knowledgeBaseUrl: String = "http://knowledge-api.knowledge-system.svc.cluster.local:8080",
    // Secret the hooks' `KB_BEARER_TOKEN` is sourced from; projected
    // from Vault by the agents-kb-bearer VaultStaticSecret.
    val knowledgeBearerSecret: String = "agents-kb-bearer",
    val knowledgeBearerSecretKey: String = "bearer",
    // ConfigMap (mounted at /etc/agent-mcp) whose claude-mcp-servers.json
    // the entrypoint seeds into Claude Code's mcpServers. Optional mount,
    // so an absent ConfigMap just means no managed MCP servers.
    val mcpServersConfigMap: String = "agents-mcp-servers",
    // MCP server profile selected by the runner entrypoint. Keep the
    // default narrow; wider diagnostic profiles are explicit opt-ins.
    val defaultMcpProfile: String = "minimal",
    // Mount the host Docker socket into runner Pods so agent sessions can run
    // Docker CLI commands and JVM Testcontainers suites from inside /workspace.
    // This is host-equivalent access. The supplemental group default is pinned
    // in platform/nix/hosts/enschede-gtx-960m-1/default.nix; override both the
    // Nix host and this property together when moving runners to another node.
    val dockerSocketEnabled: Boolean = true,
    val dockerSocketPath: String = "/var/run/docker.sock",
    val dockerSocketSupplementalGroups: List<Long> = DEFAULT_DOCKER_SOCKET_SUPPLEMENTAL_GROUPS,
    val nodeSelector: Map<String, String> = DEFAULT_NODE_SELECTOR,
    val gatewayConnectTimeoutMs: Long = 5_000,
    val gatewayReadTimeoutMs: Long = 60_000,
    // Base URL of the standing agent-gateway used for the
    // workspace-independent `/git/verify` deploy-key probe at
    // attach/create time (the per-workspace gateway sidecar only
    // exists once a runner Pod is up). Empty => verify is skipped and
    // the stored result degrades to read=write=null.
    val verifyGatewayBaseUrl: String = "",
    // Read-only GitHub API token for the branch-protection check.
    // Empty => branch protection reports null (never a hard failure).
    val githubApiToken: String = "",
    // GitHub REST base — overridable for tests / GitHub Enterprise.
    val githubApiBaseUrl: String = "https://api.github.com",
    // GitHub App credentials used to mint short-lived, repo-scoped
    // installation tokens for runner GitHub writes (`git push` via
    // HTTPS, `gh pr create`, PR comments, Actions re-runs). Requested
    // permissions stay repo-scoped: contents:write, pull_requests:write,
    // actions:write, never administration. The App's numeric id and its
    // PEM private key (PKCS#1 or PKCS#8). Both empty => minting disabled
    // and the internal token endpoint reports 503.
    val githubAppId: String = "",
    val githubAppPrivateKey: String = "",
    // Shared bearer the runner must present on the in-cluster
    // /api/v1/internal/github/installation-token call. Empty => the
    // internal endpoint is fail-closed (rejects every request) so an
    // unconfigured deployment never exposes token minting unauthed.
    val githubAppTokenBearer: String = "",
    // In-cluster URL of this service's installation-token endpoint,
    // injected into runner Pods so the `gh` wrapper can mint tokens.
    val githubAppTokenUrl: String =
        "http://agents-api.agents-system.svc.cluster.local:8082/api/v1/internal/github/installation-token",
    // Secret (in the runner's namespace) the runner's
    // GITHUB_APP_TOKEN_BEARER is sourced from; same value as
    // [githubAppTokenBearer]. optional => absent secret keeps the Pod
    // starting with the `gh` wrapper degrading to a no-op.
    val githubAppBearerSecret: String = "github-app",
    val githubAppBearerSecretKey: String = "token-bearer",
    val durableSessionRetentionSeconds: Long = 604_800,
    val durableSessionCleanupBatchSize: Int = 25,
    // In-cluster ClusterIP of the credential-worker that drives the
    // Claude Code / Codex CLI `/login` flows and writes the resulting
    // OAuth bundle to Vault. The worker is fronted by no edge route, so
    // this address skips forward-auth entirely.
    val credentialWorkerUrl: String = "http://agents-login-worker.agents-system.svc.cluster.local:8081",
    // Shared internal token presented on every credential-worker call
    // as the `x-internal-token` header. Sourced from the INTERNAL_TOKEN
    // env var. Empty => the worker rejects every proxied request with a
    // 401, so an unconfigured deployment never reaches the login flow.
    val credentialWorkerToken: String = "",
    val setups: List<AgentSetupProperties> =
        listOf(
            AgentSetupProperties(
                id = "default",
                displayName = "Default runner",
                description = "Runtime-derived default runner setup.",
                defaultSelectable = true,
            ),
        ),
) {
    init {
        require(defaultMcpProfile in VALID_MCP_PROFILES) {
            "agent-runtime.default-mcp-profile must be one of " +
                VALID_MCP_PROFILES.joinToString(", ") +
                "; got '$defaultMcpProfile'"
        }
        require(dockerSocketPath.startsWith("/")) {
            "agent-runtime.docker-socket-path must be an absolute path; got '$dockerSocketPath'"
        }
        require(dockerSocketSupplementalGroups.all { it > 0L }) {
            "agent-runtime.docker-socket-supplemental-groups must contain positive numeric gids"
        }
        require(durableSessionRetentionSeconds > 0L) {
            "agent-runtime.durable-session-retention-seconds must be positive"
        }
        require(durableSessionCleanupBatchSize > 0) {
            "agent-runtime.durable-session-cleanup-batch-size must be positive"
        }
        require(setups.isNotEmpty()) { "agent-runtime.setups must contain at least one setup" }
        require(setups.distinctBy { it.id to it.version }.size == setups.size) {
            "agent-runtime.setups must not contain duplicate id/version pairs"
        }
        val defaultSetups = setups.filter { it.defaultSelectable }
        require(defaultSetups.size == 1) {
            "agent-runtime.setups must contain exactly one default-selectable setup"
        }
        setups.forEach { setup ->
            require(setup.version > 0) {
                "agent-runtime.setups.${setup.id}.version must be positive"
            }
            require(setup.id.matches(VALID_SETUP_ID)) {
                "agent-runtime.setups.${setup.id}.id must start with a lower-case letter " +
                    "and contain only lower-case letters, digits, and dashes"
            }
            setup.defaultMcpProfile?.let { profile ->
                require(profile in VALID_MCP_PROFILES) {
                    "agent-runtime.setups.${setup.id}.default-mcp-profile must be one of " +
                        VALID_MCP_PROFILES.joinToString(", ") +
                        "; got '$profile'"
                }
            }
            setup.dockerSocketPath?.let { path ->
                require(path.startsWith("/")) {
                    "agent-runtime.setups.${setup.id}.docker-socket-path must be an absolute path; got '$path'"
                }
            }
            setup.dockerSocketSupplementalGroups?.let { groups ->
                require(groups.all { it > 0L }) {
                    "agent-runtime.setups.${setup.id}.docker-socket-supplemental-groups " +
                        "must contain positive numeric gids"
                }
            }
            require(setup.toolProfiles?.isNotEmpty() != false) {
                "agent-runtime.setups.${setup.id}.tool-profiles must not be empty"
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun setupCatalogEntries(now: Instant = Instant.now()): List<AgentSetupCatalogEntry> =
        setups.map { setup ->
            val version = AgentSetupVersion(setup.version)
            val definition =
                AgentSetupDefinition(
                    id = AgentSetupId(setup.id),
                    version = version,
                    displayName = setup.displayName,
                    description = setup.description,
                    namespace = setup.namespace ?: namespace,
                    image = setup.image ?: image,
                    imagePullPolicy = setup.imagePullPolicy ?: imagePullPolicy,
                    serviceAccount = setup.serviceAccount ?: serviceAccount,
                    gatewayPort = setup.gatewayPort ?: gatewayPort,
                    claudeCredentialsPvc = setup.claudeCredentialsPvc ?: claudeCredentialsPvc,
                    codexCredentialsPvc = setup.codexCredentialsPvc ?: codexCredentialsPvc,
                    githubDeployKeySecret = setup.githubDeployKeySecret ?: githubDeployKeySecret,
                    cliTools = setup.cliTools ?: DEFAULT_CLI_TOOLS,
                    knowledgeBaseUrl = setup.knowledgeBaseUrl ?: knowledgeBaseUrl,
                    knowledgeBearerSecret = setup.knowledgeBearerSecret ?: knowledgeBearerSecret,
                    knowledgeBearerSecretKey = setup.knowledgeBearerSecretKey ?: knowledgeBearerSecretKey,
                    mcpServersConfigMap = setup.mcpServersConfigMap ?: mcpServersConfigMap,
                    defaultMcpProfile = setup.defaultMcpProfile ?: defaultMcpProfile,
                    connectorConfig = setup.connectorConfig ?: emptyMap(),
                    toolProfiles = setup.toolProfiles ?: listOf(setup.defaultMcpProfile ?: defaultMcpProfile),
                    toolAllowlist = setup.toolAllowlist ?: emptyList(),
                    dockerSocketEnabled = setup.dockerSocketEnabled ?: dockerSocketEnabled,
                    dockerSocketPath = setup.dockerSocketPath ?: dockerSocketPath,
                    dockerSocketSupplementalGroups =
                        setup.dockerSocketSupplementalGroups ?: dockerSocketSupplementalGroups,
                    nodeSelector = setup.nodeSelector ?: nodeSelector,
                    createdAt = now,
                    updatedAt = now,
                )
            AgentSetupCatalogEntry(
                definition = definition,
                availability =
                    AgentSetupAvailability(
                        id = definition.id,
                        version = version,
                        selectable = setup.selectable,
                        defaultSelectable = setup.defaultSelectable,
                        unavailableReason = setup.unavailableReason,
                        updatedAt = now,
                    ),
            )
        }

    companion object {
        private val VALID_SETUP_ID = Regex("[a-z][a-z0-9-]{0,79}")
        private val DEFAULT_CLI_TOOLS =
            mapOf(
                "claude" to "default",
                "codex" to "default",
            )

        val VALID_MCP_PROFILES: Set<String> =
            setOf(
                "minimal",
                "frontend",
                "cluster",
                "code-intel",
                "full-diagnostic",
            )
    }
}

data class AgentSetupProperties(
    val id: String,
    val version: Long = 1,
    val displayName: String,
    val description: String? = null,
    val namespace: String? = null,
    val image: String? = null,
    val imagePullPolicy: String? = null,
    val serviceAccount: String? = null,
    val gatewayPort: Int? = null,
    val claudeCredentialsPvc: String? = null,
    val codexCredentialsPvc: String? = null,
    val githubDeployKeySecret: String? = null,
    val cliTools: Map<String, String>? = null,
    val knowledgeBaseUrl: String? = null,
    val knowledgeBearerSecret: String? = null,
    val knowledgeBearerSecretKey: String? = null,
    val mcpServersConfigMap: String? = null,
    val defaultMcpProfile: String? = null,
    val connectorConfig: Map<String, String>? = null,
    val toolProfiles: List<String>? = null,
    val toolAllowlist: List<String>? = null,
    val dockerSocketEnabled: Boolean? = null,
    val dockerSocketPath: String? = null,
    val dockerSocketSupplementalGroups: List<Long>? = null,
    val nodeSelector: Map<String, String>? = null,
    val selectable: Boolean = true,
    val defaultSelectable: Boolean = false,
    val unavailableReason: String? = null,
)

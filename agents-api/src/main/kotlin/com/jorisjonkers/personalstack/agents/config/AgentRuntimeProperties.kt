package com.jorisjonkers.personalstack.agents.config

import org.springframework.boot.context.properties.ConfigurationProperties

private const val NIXOS_DOCKER_GROUP_GID = 131L

private val DEFAULT_DOCKER_SOCKET_SUPPLEMENTAL_GROUPS =
    listOf(NIXOS_DOCKER_GROUP_GID)

private val DEFAULT_NODE_SELECTOR =
    mapOf(
        "agents/node" to "enschede-gtx-960m-1",
        "agents/capability-docker-socket" to "true",
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
    }

    companion object {
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

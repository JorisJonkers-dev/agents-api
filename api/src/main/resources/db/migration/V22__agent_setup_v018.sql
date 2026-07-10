-- Retire default@2 (v0.16.0, old agents/* node-selector keys) and seed
-- default@3 with the v0.18.0 image and the correct personal-stack/* keys.
-- The node-selector in default@2 was copied verbatim from default@1 whose
-- V15 seed used the old "agents/node" label prefix — that prefix was
-- never present on the cluster and caused runners to sit Pending forever.
-- default@3 uses the bracket-escaped personal-stack/* keys that match both
-- application.yml and the cluster node labels.

UPDATE agent_setup_availability
SET selectable            = FALSE,
    default_selectable    = FALSE,
    unavailable_reason    = 'Replaced by default@3 (agent-runtime v0.18.0, corrected node-selector keys).',
    updated_at            = NOW()
WHERE setup_id            = 'default'
  AND setup_version       = 2;

INSERT INTO agent_setup_definitions (
    setup_id,
    setup_version,
    display_name,
    description,
    namespace,
    image,
    image_pull_policy,
    service_account,
    gateway_port,
    claude_credentials_pvc,
    codex_credentials_pvc,
    github_deploy_key_secret,
    cli_tools,
    knowledge_base_url,
    knowledge_bearer_secret,
    knowledge_bearer_secret_key,
    mcp_servers_config_map,
    default_mcp_profile,
    connector_config,
    tool_profiles,
    tool_allowlist,
    docker_socket_enabled,
    docker_socket_path,
    docker_socket_supplemental_groups,
    node_selector,
    created_at,
    updated_at
)
SELECT
    setup_id,
    3,
    display_name,
    description,
    namespace,
    'ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:v0.18.0',
    'IfNotPresent',
    service_account,
    gateway_port,
    claude_credentials_pvc,
    codex_credentials_pvc,
    github_deploy_key_secret,
    cli_tools,
    knowledge_base_url,
    knowledge_bearer_secret,
    knowledge_bearer_secret_key,
    mcp_servers_config_map,
    default_mcp_profile,
    connector_config,
    tool_profiles,
    tool_allowlist,
    docker_socket_enabled,
    docker_socket_path,
    docker_socket_supplemental_groups,
    '{"personal-stack/node":"enschede-gtx-960m-1","personal-stack/capability-docker-socket":"true"}',
    NOW(),
    NOW()
FROM agent_setup_definitions
WHERE setup_id      = 'default'
  AND setup_version = 2;

INSERT INTO agent_setup_availability (
    setup_id,
    setup_version,
    selectable,
    default_selectable,
    unavailable_reason,
    updated_at
) VALUES (
    'default',
    3,
    TRUE,
    TRUE,
    NULL,
    NOW()
);

UPDATE workspaces
SET current_runner_setup_version = 3,
    runner_setup_generation      = runner_setup_generation + 1
WHERE current_runner_setup_id      = 'default'
  AND current_runner_setup_version = 2;

UPDATE workspace_agent_sessions
SET current_setup_version = 3
WHERE current_setup_id      = 'default'
  AND current_setup_version = 2;

-- Migrate any pending setup references still pointing at default@2
-- so in-flight promote operations do not revive the retired image.
UPDATE workspaces
SET pending_runner_setup_version = 3
WHERE pending_runner_setup_id      = 'default'
  AND pending_runner_setup_version = 2;

UPDATE workspace_agent_sessions
SET pending_setup_version = 3
WHERE pending_setup_id      = 'default'
  AND pending_setup_version = 2;

UPDATE agent_setup_availability
SET selectable = FALSE,
    default_selectable = FALSE,
    unavailable_reason = 'Replaced by default@2 JorisJonkers-dev runner image.',
    updated_at = NOW()
WHERE setup_id = 'default'
  AND setup_version = 1;

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
    2,
    display_name,
    description,
    namespace,
    'ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:v0.16.0',
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
    node_selector,
    NOW(),
    NOW()
FROM agent_setup_definitions
WHERE setup_id = 'default'
  AND setup_version = 1;

INSERT INTO agent_setup_availability (
    setup_id,
    setup_version,
    selectable,
    default_selectable,
    unavailable_reason,
    updated_at
) VALUES (
    'default',
    2,
    TRUE,
    TRUE,
    NULL,
    NOW()
);

UPDATE workspaces
SET current_runner_setup_version = 2,
    runner_setup_generation = runner_setup_generation + 1
WHERE current_runner_setup_id = 'default'
  AND current_runner_setup_version = 1;

UPDATE workspace_agent_sessions
SET current_setup_version = 2
WHERE current_setup_id = 'default'
  AND current_setup_version = 1;

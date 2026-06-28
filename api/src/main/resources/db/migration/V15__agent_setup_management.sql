CREATE TABLE agent_setup_definitions (
    setup_id                          VARCHAR(80)  NOT NULL,
    setup_version                     BIGINT       NOT NULL,
    display_name                      VARCHAR(120) NOT NULL,
    description                       TEXT,
    namespace                         TEXT         NOT NULL,
    image                             TEXT         NOT NULL,
    image_pull_policy                 VARCHAR(32)  NOT NULL,
    service_account                   TEXT         NOT NULL,
    gateway_port                      INTEGER      NOT NULL,
    claude_credentials_pvc            TEXT         NOT NULL,
    codex_credentials_pvc             TEXT         NOT NULL,
    github_deploy_key_secret          TEXT         NOT NULL,
    cli_tools                         TEXT         NOT NULL,
    knowledge_base_url                TEXT         NOT NULL,
    knowledge_bearer_secret           TEXT         NOT NULL,
    knowledge_bearer_secret_key       TEXT         NOT NULL,
    mcp_servers_config_map            TEXT         NOT NULL,
    default_mcp_profile               VARCHAR(64)  NOT NULL,
    connector_config                  TEXT         NOT NULL,
    tool_profiles                     TEXT         NOT NULL,
    tool_allowlist                    TEXT         NOT NULL,
    docker_socket_enabled             BOOLEAN      NOT NULL,
    docker_socket_path                TEXT         NOT NULL,
    docker_socket_supplemental_groups TEXT         NOT NULL,
    node_selector                     TEXT         NOT NULL,
    created_at                        TIMESTAMPTZ  NOT NULL,
    updated_at                        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (setup_id, setup_version)
);

CREATE TABLE agent_setup_availability (
    setup_id           VARCHAR(80) NOT NULL,
    setup_version      BIGINT      NOT NULL,
    selectable         BOOLEAN     NOT NULL,
    default_selectable BOOLEAN     NOT NULL,
    unavailable_reason TEXT,
    updated_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (setup_id, setup_version),
    FOREIGN KEY (setup_id, setup_version)
        REFERENCES agent_setup_definitions(setup_id, setup_version),
    CHECK (NOT default_selectable OR selectable)
);

CREATE INDEX idx_agent_setup_availability_selectable
    ON agent_setup_availability (selectable, default_selectable);

CREATE UNIQUE INDEX idx_agent_setup_availability_default
    ON agent_setup_availability (default_selectable)
    WHERE default_selectable;

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
) VALUES
(
    'default',
    1,
    'Default runner',
    'Runtime-derived default runner setup.',
    'agents-system',
    'ghcr.io/extratoast/agents/agent-runner:latest',
    'Always',
    'agent-runner',
    8090,
    'claude-credentials',
    'codex-credentials',
    'agents-github-deploy-key',
    '{"claude":"default","codex":"default"}',
    'http://knowledge-api.knowledge-system.svc.cluster.local:8080',
    'agents-kb-bearer',
    'bearer',
    'agents-mcp-servers',
    'minimal',
    '{}',
    '["minimal"]',
    '[]',
    TRUE,
    '/var/run/docker.sock',
    '[131]',
    '{"agents/capability-docker-socket":"true","agents/node":"enschede-gtx-960m-1"}',
    NOW(),
    NOW()
),
(
    'legacy',
    1,
    'Legacy runner',
    'Fallback for rows whose original runner setup cannot be inferred.',
    'legacy',
    'legacy',
    'IfNotPresent',
    'legacy',
    8090,
    'legacy',
    'legacy',
    'legacy',
    '{}',
    'legacy',
    'legacy',
    'legacy',
    'legacy',
    'minimal',
    '{}',
    '[]',
    '[]',
    FALSE,
    '/var/run/docker.sock',
    '[]',
    '{}',
    NOW(),
    NOW()
);

INSERT INTO agent_setup_availability (
    setup_id,
    setup_version,
    selectable,
    default_selectable,
    unavailable_reason,
    updated_at
) VALUES
('default', 1, TRUE, TRUE, NULL, NOW()),
('legacy', 1, FALSE, FALSE, 'Existing row predates persisted setup selection.', NOW());

ALTER TABLE workspaces
    ADD COLUMN current_runner_setup_id                VARCHAR(80) NOT NULL DEFAULT 'legacy',
    ADD COLUMN current_runner_setup_version           BIGINT      NOT NULL DEFAULT 1,
    ADD COLUMN pending_runner_setup_id                VARCHAR(80),
    ADD COLUMN pending_runner_setup_version           BIGINT,
    ADD COLUMN runner_setup_generation                BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN runner_setup_operation                 VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    ADD COLUMN runner_setup_operation_started_at      TIMESTAMPTZ,
    ADD COLUMN runner_setup_operation_updated_at      TIMESTAMPTZ;

UPDATE workspaces
SET current_runner_setup_id = 'default',
    current_runner_setup_version = 1,
    runner_setup_generation = 1
WHERE pod_name IS NOT NULL
   OR pvc_name IS NOT NULL
   OR gateway_endpoint IS NOT NULL;

ALTER TABLE workspaces
    ADD CONSTRAINT fk_workspaces_current_runner_setup
        FOREIGN KEY (current_runner_setup_id, current_runner_setup_version)
        REFERENCES agent_setup_definitions(setup_id, setup_version),
    ADD CONSTRAINT fk_workspaces_pending_runner_setup
        FOREIGN KEY (pending_runner_setup_id, pending_runner_setup_version)
        REFERENCES agent_setup_definitions(setup_id, setup_version),
    ADD CONSTRAINT ck_workspaces_pending_runner_setup_pair
        CHECK (
            (pending_runner_setup_id IS NULL AND pending_runner_setup_version IS NULL) OR
            (pending_runner_setup_id IS NOT NULL AND pending_runner_setup_version IS NOT NULL)
        );

CREATE INDEX idx_workspaces_current_runner_setup
    ON workspaces (current_runner_setup_id, current_runner_setup_version);

CREATE INDEX idx_workspaces_pending_runner_setup
    ON workspaces (pending_runner_setup_id, pending_runner_setup_version)
    WHERE pending_runner_setup_id IS NOT NULL;

ALTER TABLE workspace_agent_sessions
    ADD COLUMN current_setup_id      VARCHAR(80) NOT NULL DEFAULT 'legacy',
    ADD COLUMN current_setup_version BIGINT      NOT NULL DEFAULT 1,
    ADD COLUMN pending_setup_id      VARCHAR(80),
    ADD COLUMN pending_setup_version BIGINT;

UPDATE workspace_agent_sessions
SET current_setup_id = 'default',
    current_setup_version = 1
WHERE gateway_agent_id IS NOT NULL;

ALTER TABLE workspace_agent_sessions
    ADD CONSTRAINT fk_was_current_setup
        FOREIGN KEY (current_setup_id, current_setup_version)
        REFERENCES agent_setup_definitions(setup_id, setup_version),
    ADD CONSTRAINT fk_was_pending_setup
        FOREIGN KEY (pending_setup_id, pending_setup_version)
        REFERENCES agent_setup_definitions(setup_id, setup_version),
    ADD CONSTRAINT ck_was_pending_setup_pair
        CHECK (
            (pending_setup_id IS NULL AND pending_setup_version IS NULL) OR
            (pending_setup_id IS NOT NULL AND pending_setup_version IS NOT NULL)
        );

CREATE INDEX idx_was_current_setup
    ON workspace_agent_sessions (current_setup_id, current_setup_version);

CREATE INDEX idx_was_pending_setup
    ON workspace_agent_sessions (pending_setup_id, pending_setup_version)
    WHERE pending_setup_id IS NOT NULL;

CREATE TABLE setup_restart_events (
    id                 UUID        PRIMARY KEY,
    workspace_id       UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    session_id         UUID        REFERENCES workspace_agent_sessions(id) ON DELETE SET NULL,
    from_setup_id      VARCHAR(80),
    from_setup_version BIGINT,
    to_setup_id        VARCHAR(80) NOT NULL,
    to_setup_version   BIGINT      NOT NULL,
    status             VARCHAR(32) NOT NULL,
    reason             TEXT,
    message            TEXT,
    requested_at       TIMESTAMPTZ NOT NULL,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (from_setup_id, from_setup_version)
        REFERENCES agent_setup_definitions(setup_id, setup_version),
    FOREIGN KEY (to_setup_id, to_setup_version)
        REFERENCES agent_setup_definitions(setup_id, setup_version),
    CHECK (
        (from_setup_id IS NULL AND from_setup_version IS NULL) OR
        (from_setup_id IS NOT NULL AND from_setup_version IS NOT NULL)
    )
);

CREATE INDEX idx_setup_restart_events_workspace
    ON setup_restart_events (workspace_id, requested_at);

CREATE INDEX idx_setup_restart_events_session
    ON setup_restart_events (session_id, requested_at)
    WHERE session_id IS NOT NULL;

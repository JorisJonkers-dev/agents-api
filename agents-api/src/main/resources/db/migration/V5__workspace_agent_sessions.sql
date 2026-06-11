CREATE TABLE workspace_agent_sessions (
    id                  UUID PRIMARY KEY,
    workspace_id        UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    kind                TEXT        NOT NULL,
    gateway_agent_id    TEXT,
    status              TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_was_workspace_id ON workspace_agent_sessions (workspace_id);

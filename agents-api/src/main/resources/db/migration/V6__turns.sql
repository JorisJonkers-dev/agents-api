CREATE TABLE turns (
    id            UUID PRIMARY KEY,
    session_id    UUID        NOT NULL REFERENCES workspace_agent_sessions(id) ON DELETE CASCADE,
    role          TEXT        NOT NULL,
    body          TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_turns_session_created ON turns (session_id, created_at);

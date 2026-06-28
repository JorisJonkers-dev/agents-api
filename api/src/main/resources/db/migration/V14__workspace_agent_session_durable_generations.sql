ALTER TABLE workspace_agent_sessions
    ADD COLUMN epoch                BIGINT      NOT NULL DEFAULT 1,
    ADD COLUMN generation           BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN gateway_bound_at     TIMESTAMPTZ,
    ADD COLUMN retained_until       TIMESTAMPTZ,
    ADD COLUMN cleanup_requested_at TIMESTAMPTZ;

CREATE INDEX idx_was_retained_until
    ON workspace_agent_sessions (retained_until)
    WHERE retained_until IS NOT NULL;

CREATE INDEX idx_was_cleanup_requested_at
    ON workspace_agent_sessions (cleanup_requested_at)
    WHERE cleanup_requested_at IS NOT NULL;

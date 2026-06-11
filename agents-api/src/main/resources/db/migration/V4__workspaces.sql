CREATE TABLE workspaces (
    id                UUID PRIMARY KEY,
    name              TEXT        NOT NULL,
    repo_url          TEXT,
    branch            TEXT,
    pod_name          TEXT,
    pvc_name          TEXT,
    gateway_endpoint  TEXT,
    -- VARCHAR(32) instead of TEXT because the column is indexed; H2
    -- (used by jOOQ's DDL simulation at codegen time) cannot put an
    -- index on a CLOB-shaped column, and Postgres treats VARCHAR(N)
    -- and TEXT identically anyway.
    status            VARCHAR(32) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);

-- Full index (no WHERE predicate) — jOOQ's H2-backed DDL simulation
-- doesn't parse partial indexes, and the cardinality of the status
-- column is low enough that the additional storage cost is minimal.
CREATE INDEX idx_workspaces_status ON workspaces (status);

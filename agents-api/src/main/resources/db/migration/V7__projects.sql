CREATE TABLE projects (
    id            UUID PRIMARY KEY,
    name          TEXT         NOT NULL,
    -- VARCHAR(64) because `slug` carries a UNIQUE index — H2 (used by
    -- jOOQ's DDL simulation at codegen time) can't index a CLOB.
    slug          VARCHAR(64)  NOT NULL UNIQUE,
    description   TEXT         NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_projects_created_at ON projects (created_at DESC);

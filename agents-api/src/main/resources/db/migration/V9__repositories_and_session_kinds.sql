-- Repository as a first-class entity. Previously a GithubLink row
-- was scoped to one Project; the operator's mental model has the
-- repository (with its deploy key) shared across many Projects, so
-- the table is owner-less and the new project_repositories junction
-- carries the M:N membership.
--
-- VARCHAR(80) on `name` because the UNIQUE index can't sit on a CLOB
-- under H2 (jOOQ's DDL simulation backend at codegen time).
CREATE TABLE repositories (
    id                       UUID PRIMARY KEY,
    name                     VARCHAR(80) NOT NULL UNIQUE,
    repo_url                 TEXT        NOT NULL,
    default_branch           TEXT        NOT NULL DEFAULT 'main',
    vault_key_path           TEXT        NOT NULL,
    deploy_key_fingerprint   TEXT,
    deploy_key_added_at      TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL
);

-- Junction: a project lists repositories, a repository can be in
-- many projects.
CREATE TABLE project_repositories (
    project_id    UUID        NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    repository_id UUID        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    linked_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, repository_id)
);

CREATE INDEX idx_project_repositories_repository ON project_repositories (repository_id);

-- Data migration: every github_links row becomes a repository plus a
-- junction row. The Vault path stays exactly as it was (already
-- (project, link)-keyed), so existing secrets remain readable; the
-- new per-repository write path coexists in VaultDeployKeyStore for
-- the migration window.
--
-- NOTE: github_links.name is UNIQUE per project, not globally. Two
-- projects may have already added a link called "personal-stack",
-- and the repositories table is globally unique on name. The
-- migration disambiguates by appending the project id suffix when a
-- collision would otherwise occur.
INSERT INTO repositories (id, name, repo_url, default_branch,
                          vault_key_path, deploy_key_fingerprint,
                          deploy_key_added_at, created_at, updated_at)
SELECT
    gl.id,
    CASE
        WHEN dup.cnt > 1
            THEN gl.name || '-' || substring(gl.project_id::text, 1, 8)
        ELSE gl.name
    END,
    gl.repo_url,
    gl.default_branch,
    gl.vault_key_path,
    gl.deploy_key_fingerprint,
    gl.deploy_key_added_at,
    gl.created_at,
    gl.updated_at
FROM github_links gl
JOIN (
    SELECT name, COUNT(*) AS cnt FROM github_links GROUP BY name
) dup ON dup.name = gl.name;

INSERT INTO project_repositories (project_id, repository_id, linked_at)
SELECT project_id, id, COALESCE(deploy_key_added_at, created_at)
FROM github_links;

-- Workspace gains a per-repository pointer + the optional project
-- context + a kind discriminator. `kind` defaults to REPO_BACKED so
-- existing rows render the same shape they used to under the
-- workspace tab.
ALTER TABLE workspaces
    ADD COLUMN repository_id UUID REFERENCES repositories(id) ON DELETE SET NULL,
    ADD COLUMN project_id    UUID REFERENCES projects(id) ON DELETE SET NULL,
    ADD COLUMN kind          VARCHAR(16) NOT NULL DEFAULT 'REPO_BACKED';

UPDATE workspaces SET repository_id = github_link_id WHERE github_link_id IS NOT NULL;

CREATE INDEX idx_workspaces_repository ON workspaces (repository_id);
CREATE INDEX idx_workspaces_project ON workspaces (project_id);
-- `kind` cardinality is low; a full index is still cheaper than
-- table scans on the dashboard query and H2's DDL simulator (used at
-- jOOQ codegen) does not parse partial indexes.
CREATE INDEX idx_workspaces_kind ON workspaces (kind);

-- Chat sessions: independent of workspaces, tied to a user. No Pod
-- is spawned — chat is a plain LLM conversation surface.
CREATE TABLE chat_sessions (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL,
    title      VARCHAR(120),
    status     VARCHAR(32)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_chat_sessions_user_status ON chat_sessions (user_id, status);

CREATE TABLE chat_session_messages (
    id              UUID PRIMARY KEY,
    chat_session_id UUID         NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role            VARCHAR(16)  NOT NULL,
    body            TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_chat_session_messages_session_time
    ON chat_session_messages (chat_session_id, created_at);

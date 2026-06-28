-- A linked GitHub repository under a Project. Each row points to a
-- Vault KV-v2 path that stores the deploy key (private_key,
-- public_key, known_hosts) provisioned for this single repo.
CREATE TABLE github_links (
    id                       UUID PRIMARY KEY,
    project_id               UUID         NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- VARCHAR(80) because the table carries UNIQUE(project_id, name);
    -- H2 (jOOQ's DDL simulation backend) cannot index a CLOB.
    name                     VARCHAR(80)  NOT NULL,
    repo_url                 TEXT         NOT NULL,
    default_branch           TEXT         NOT NULL DEFAULT 'main',
    -- Path under the Vault `secret/data/` mount where the deploy key
    -- lives. Stored relative so a future Vault namespace change
    -- doesn't require a data migration.
    vault_key_path           TEXT        NOT NULL,
    deploy_key_fingerprint   TEXT,
    deploy_key_added_at      TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    UNIQUE (project_id, name)
);

CREATE INDEX idx_github_links_project ON github_links (project_id);

-- Workspaces optionally point at a single GithubLink. Adding the
-- column nullable so existing ad-hoc workspaces (created with just
-- a repoUrl) keep working; new project-backed workspaces fill it in.
ALTER TABLE workspaces ADD COLUMN github_link_id UUID REFERENCES github_links(id) ON DELETE SET NULL;
CREATE INDEX idx_workspaces_github_link ON workspaces (github_link_id);

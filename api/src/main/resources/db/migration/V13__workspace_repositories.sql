-- A workspace can clone more than one repository. The primary repo stays
-- denormalised on workspaces (repository_id / repo_url) and drives REPO_URL;
-- this junction carries the additional repos that become REPO_URLS in the
-- runner Pod. is_primary marks the backfilled primary so the orchestrator can
-- exclude it from REPO_URLS without a second lookup.
CREATE TABLE workspace_repositories (
    workspace_id  UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id UUID        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    is_primary    BOOLEAN     NOT NULL DEFAULT FALSE,
    attached_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, repository_id)
);

CREATE INDEX idx_workspace_repositories_repository ON workspace_repositories (repository_id);

-- Backfill the primary repo for existing repo-backed workspaces so the model
-- is consistent from day one.
INSERT INTO workspace_repositories (workspace_id, repository_id, is_primary, attached_at)
SELECT id, repository_id, TRUE, created_at
FROM workspaces
WHERE repository_id IS NOT NULL;

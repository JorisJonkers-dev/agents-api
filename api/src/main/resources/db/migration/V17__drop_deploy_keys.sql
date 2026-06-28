-- Retire SSH deploy keys: GitHub access is now granted solely by the
-- installed GitHub App (short-lived installation tokens over HTTPS), so
-- the per-repo key columns and the Vault path are dead. The matching
-- Vault secrets are deleted best-effort by the application on repo/link
-- delete; the column drop here is the authoritative "no longer used"
-- signal. The branch-protection verification columns are kept.
--
-- Repository (first-class) loses its key columns and the deploy-key
-- read/write verification columns; verify_default_branch_protected,
-- verify_checked_at, and verify_messages are retained for the
-- branch-protection signal that survives the deploy-key removal.
ALTER TABLE repositories
    DROP COLUMN vault_key_path,
    DROP COLUMN deploy_key_fingerprint,
    DROP COLUMN deploy_key_added_at,
    DROP COLUMN verify_read,
    DROP COLUMN verify_write;

-- Legacy project-scoped GithubLink rows are kept read-only so existing
-- workspaces keep working, but they no longer carry a deploy key.
ALTER TABLE github_links
    DROP COLUMN vault_key_path,
    DROP COLUMN deploy_key_fingerprint,
    DROP COLUMN deploy_key_added_at;

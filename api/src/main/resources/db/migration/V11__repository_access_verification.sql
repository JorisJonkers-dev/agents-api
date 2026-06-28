-- Cached deploy-key verification outcome, mirrored onto the row so
-- the UI can render read/write/protected without a live probe on
-- every page load. Re-run on demand via POST /repositories/{id}/verify
-- and refreshed automatically after every AttachRepositoryDeployKey.
--
-- All three booleans are nullable: null means "not yet verified" or
-- "inconclusive" (gateway unreachable / no GitHub token), distinct
-- from an explicit false. Messages are stored as a single newline-
-- joined TEXT (not an array) so the jOOQ H2 DDL simulator at codegen
-- time stays happy.
ALTER TABLE repositories
    ADD COLUMN verify_read                    BOOLEAN,
    ADD COLUMN verify_write                   BOOLEAN,
    ADD COLUMN verify_default_branch_protected BOOLEAN,
    ADD COLUMN verify_checked_at              TIMESTAMPTZ,
    ADD COLUMN verify_messages                TEXT;

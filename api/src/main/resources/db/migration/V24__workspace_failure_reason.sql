-- Workspace Status moves Preparing to Ready, or to Failed with a reason the
-- UI shows (#63). Nullable: most rows never reach FAILED, and existing rows
-- have none.
ALTER TABLE workspaces ADD COLUMN failure_reason TEXT;

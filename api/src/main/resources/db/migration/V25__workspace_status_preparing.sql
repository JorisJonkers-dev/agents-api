-- Workspace Status glossary is Preparing, Ready, Failed or Destroyed (#63).
-- PENDING is renamed to PREPARING; STARTING and IDLE never got their own
-- UI treatment and collapse into the status they actually meant.
UPDATE workspaces SET status = 'PREPARING' WHERE status IN ('PENDING', 'STARTING');
UPDATE workspaces SET status = 'READY' WHERE status = 'IDLE';

-- The value set is closed now that WorkspaceStatus dropped STARTING and
-- IDLE, so a CHECK constraint turns a future bad write into a rejected
-- statement instead of an unreadable row discovered at valueOf() time.
ALTER TABLE workspaces ADD CONSTRAINT chk_workspaces_status
    CHECK (status IN ('PREPARING', 'READY', 'FAILED', 'DESTROYED'));

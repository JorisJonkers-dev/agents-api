-- Store the native CLI session id so a resume flag can be passed on
-- wake/re-attach without starting a fresh session. Nullable because:
--   - SHELL sessions have no native CLI id
--   - Codex session id discovery is async (captured after spawn)
--   - Old rows have no id

ALTER TABLE workspace_agent_sessions
    ADD COLUMN cli_session_id TEXT,
    ADD COLUMN run_mode       TEXT NOT NULL DEFAULT 'INTERACTIVE';

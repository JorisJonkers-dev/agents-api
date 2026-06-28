-- Chat sessions gain a `kind` so the redesigned UI can mark a session
-- as KB-mode (KNOWLEDGE) vs the existing no-Pod surface (PLAIN). Existing
-- rows default to PLAIN, which is exactly their current behaviour.
ALTER TABLE chat_sessions
    ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'PLAIN';

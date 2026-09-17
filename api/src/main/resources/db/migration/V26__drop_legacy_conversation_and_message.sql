-- The `conversation` / `message` tables backed the original assistant
-- surface (V1/V2). No frontend in this workspace calls
-- /api/v1/conversations any more -- agents-ui talks to the newer
-- /api/v1/chat-sessions surface exclusively. That surface takes over
-- the `conversations` name in the next migration, so the old rows are
-- dropped outright rather than migrated: they are unread and nothing
-- reads this table today.
--
-- `message` first: it carries the FK to `conversation`.
DROP TABLE message;
DROP TABLE conversation;

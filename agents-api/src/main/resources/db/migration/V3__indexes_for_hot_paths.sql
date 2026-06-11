-- Composite indexes on the two query shapes that fire on every
-- chat page load:
--   * "list my conversations, newest first"  → conversation (user_id, created_at DESC)
--   * "fetch this conversation's messages"   → message (conversation_id, created_at)
--
-- V1/V2 already index user_id and conversation_id respectively, but
-- those are single-column — the DESC / sort order on created_at falls
-- off the index and Postgres does a filter+sort. Composite indexes
-- let the planner serve both queries index-only.
CREATE INDEX IF NOT EXISTS idx_conversation_user_id_created_at
    ON conversation (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_message_conversation_id_created_at
    ON message (conversation_id, created_at);

-- The single-column indexes from V1/V2 become redundant with these,
-- but dropping them here risks blocking the migration on an exclusive
-- lock if the table is large. Defer the drop to a follow-up migration
-- after we've confirmed the composites are being used (via
-- pg_stat_user_indexes).

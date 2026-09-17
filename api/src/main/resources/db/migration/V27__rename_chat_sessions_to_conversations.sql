-- The no-Pod chat surface takes over the `Conversation` name now that
-- the legacy model is gone (V26). RENAME preserves every row -- the
-- point of doing this in-place rather than as a drop/recreate is that
-- existing chats remain available afterwards, just addressed through
-- the new name.
ALTER TABLE chat_sessions RENAME TO conversations;
ALTER TABLE chat_session_messages RENAME TO conversation_messages;
ALTER TABLE conversation_messages RENAME COLUMN chat_session_id TO conversation_id;

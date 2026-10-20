-- knowledge-api is retired, and with it the KB_URL / KB_BEARER_TOKEN env a
-- runner Pod got from these columns. Nothing reads or writes them any more.
--
-- Forward-only: an agents-api older than this migration writes these NOT NULL
-- columns on every setup upsert, so rolling back past it needs the columns
-- re-added first.
-- One column per statement: jOOQ's DDL parser, which codegen runs over these
-- files, rejects several DROP COLUMN clauses in one ALTER TABLE.
ALTER TABLE agent_setup_definitions DROP COLUMN IF EXISTS knowledge_base_url;
ALTER TABLE agent_setup_definitions DROP COLUMN IF EXISTS knowledge_bearer_secret;
ALTER TABLE agent_setup_definitions DROP COLUMN IF EXISTS knowledge_bearer_secret_key;

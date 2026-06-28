-- Per-user Claude/Codex login credentials, captured by the agents-login
-- worker and stored here (replacing the Vault `agents/claude-oauth` /
-- `agents/codex-oauth` paths + their VaultStaticSecret projections). Keyed by
-- the forward-auth identity (X-User-Id) so each user's tokens are isolated;
-- the runner is injected with its workspace owner's credential at provision.
--
-- payload is a JSON object of provider-specific fields (Claude: oauth_token;
-- Codex: auth_json + config_toml), stored as text (no jsonb operators needed,
-- and the jOOQ codegen DDL simulation does not model jsonb). token_valid /
-- validated_at record the post-capture provider probe; an invalid token is
-- surfaced for re-login but never blocks a runner from starting. provider is
-- constrained to CLAUDE/CODEX by the application enum.
CREATE TABLE agent_oauth_credentials (
    user_id      VARCHAR(255) NOT NULL,
    provider     VARCHAR(32)  NOT NULL,
    payload      TEXT         NOT NULL,
    token_valid  BOOLEAN,
    validated_at TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (user_id, provider)
);

# Agent Login lives only on the home volume

Agent Login used to go through a separate `agents-login` worker. It captured Claude and Codex OAuth tokens, agents-api stored them in Postgres (`agent_oauth_credentials`, which had replaced Vault), and each runner got a copy as a Secret. We now let the user sign in from a terminal in any Agent Session, and the agent CLI writes its own login files to the home volume. Nothing else stores them. If the volume is lost, the user signs in again. That costs less than keeping a token-capture worker, a database table and per-Workspace Secrets that could all fall out of sync.

## Consequences

- The `agents-login` worker, the `agent_oauth_credentials` table and the per-Workspace credential Secrets are removed.
- Agent Login is never in Vault. Do not add it there: every other secret comes from Vault, so a reader will expect Agent Login to as well.

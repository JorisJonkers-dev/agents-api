# The API and agents run as separate users

Agent Sessions share the API container, so an agent running as the API's user could read the Postgres credentials and the GitHub App private key, or kill the API. The JVM runs as the `api` user and owns every secret with mode 0400. Agent Sessions start as the `agent` user. GitHub tokens reach agents through a git credential helper, which calls the API over a unix socket. The API checks the caller's uid with `SO_PEERCRED` and mints a short-lived token scoped to the Workspace's Repository. The shared bearer token between runner and API is removed.

## Considered Options

- **One user for everything:** simpler, but every agent would have the GitHub App key and database access.
- **Localhost HTTP with no authentication:** any process in the pod could mint tokens, including one started by a dependency's install script.

## Consequences

- The container needs `SETUID` and `SETGID` so the API can start Agent Sessions as the `agent` user.
- Vault secrets mounted for agents (for example memory and Overleaf) are readable by the `agent` user; API secrets are not.

# Agent Sessions run inside the API container

Agent Sessions used to run in a separate runner Pod for each Workspace. agents-api created that Pod, its PVC and its Service through the Kubernetes API, and agent-gateway drove tmux inside it. That design needed a boot lease, versioned runner Setups, RBAC over pods, PVCs and secrets, and a copy of the host Docker socket in every runner. We now run every Workspace and Agent Session inside the single agents-api container, with the gateway's tmux code merged into agents-api. Two volumes hold state: one for Workspaces and one for home-directory state. The pod has one replica, uses the `Recreate` strategy and stays on one node.

## Considered Options

- **Pod per Workspace (the previous design):** strong isolation, but most of the code and most of the failures were in orchestration.
- **API and gateway as two processes in one image:** less rewriting, but it keeps a network hop and a second service contract for no benefit.

## Consequences

- Every Agent Session shares one trust domain: filesystem, processes and network. This is acceptable for a single-user estate.
- The host Docker socket is removed. Testcontainers does not work inside Agent Sessions until a rootless Docker sidecar is added.
- A redeploy or an out-of-memory kill ends every agent process. Those Agent Sessions become Suspended, and the user can Resume them.
- The Setup concept, the boot lease and the Kubernetes RBAC for agents-api are removed.

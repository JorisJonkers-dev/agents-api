# agents-api

> **Retired on 2026-10-23.** agents-api, agents-ui and the agent runners were
> parked at zero replicas on 2026-09-01, when Hermes replaced them, and the
> operator retired them on 2026-10-23. JorisJonkers-dev/fleet-infra#531 removes
> them from the cluster. Nothing deploys from this repository any more; it is
> kept for its history. Whether to archive it is the operator's call.

Kotlin/Spring API service for agent workspaces, sessions, credentials, and
runner orchestration.

## What It Is

`agents-api` is a JVM API service for the JorisJonkers.dev agent platform. It
owns workspace/project data, chat/session APIs, credential relay endpoints, and
the Kubernetes orchestration calls used to create per-workspace runner Pods.

## Local Use

```bash
./gradlew :api:test
./gradlew :api:integrationTest
./gradlew :api:bootRun
```

API consumers should use the published OpenAPI contract or generated clients
rather than copying internal service code.

## Related

- API contract: [CONTRACT.md](./CONTRACT.md)
- Client package: `@jorisjonkers-dev/agents-api-client`

## Links

- [Organization profile](https://github.com/JorisJonkers-dev)
- [Security policy](https://github.com/JorisJonkers-dev/.github/security/policy)
- [Changelog](./CHANGELOG.md)
- [License](./LICENSE)

Copyright (c) Joris Jonkers. Source available for viewing only; use, copying,
modification, redistribution, deployment, or reuse is not licensed. See
[LICENSE](./LICENSE).

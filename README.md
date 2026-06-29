# agents-api

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

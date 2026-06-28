# agents-api OpenAPI contract

This service emits its OpenAPI 3.1 spec via springdoc at
`/api/v1/api-docs`. The spec is committed to
`client-spec/openapi/agents-api.json` and used to publish Java, Kotlin,
and TypeScript clients. CI fails any PR that lets the committed spec,
the live springdoc output, or the generated clients drift apart.

## Pieces

- `client-spec/openapi/agents-api.json` — pinned spec (committed).
- `clients/typescript` — generated TypeScript client package
  `@jorisjonkers-dev/agents-api-client`.
- `clients/java` and `clients/kotlin` — generated Maven clients
  `dev.jorisjonkers:agents-api-client-java` and
  `dev.jorisjonkers:agents-api-client-kotlin`.
- `OpenApiSpecExportTest` (integration tier, tag
  `contract-export`) — boots the full Spring context and dumps the
  spec to the committed path.
- Gradle task `:api:exportOpenApiSpec` —
  runs only the tagged test; the default `integrationTest` task
  excludes the tag.
- `.github/workflows/ci.yml` — the `API Contract` job runs
  `api-contract-checks`, the Gradle export drift gate, and
  `oasdiff` breaking-change checks on `client-spec/openapi/agents-api.json`.
  The `Client Dry Run` job generates, builds, and packs the TypeScript
  client. All gates feed the
  `Pipeline Complete` aggregator.

## Regenerate after an API change

```bash
./gradlew :api:exportOpenApiSpec
pnpm --filter @jorisjonkers-dev/agents-api-client generate
git add client-spec/openapi/agents-api.json clients/typescript/src/generated
```

Commit the two files alongside the controller / DTO change in the
same PR.

## What CI failures look like

- **`openapi.json` drift.** The Gradle export step in the
  `API Contract` job overwrites the committed file with the live
  springdoc output, then `git diff --exit-code` flags the change.
  Resolve by running `./gradlew :api:exportOpenApiSpec`
  locally and committing the result.
- **Breaking API drift.** `oasdiff` compares the PR spec against
  `origin/main:client-spec/openapi/agents-api.json` and fails on
  warnings.
- **Generated client drift.** Resolve by running
  `pnpm --filter @jorisjonkers-dev/agents-api-client generate`.

## Runtime Setup Version

The JorisJonkers-dev runner image is `default@2`. A change to the
default runner image, pull policy, or other provisioning behavior must
publish a new `default@N` setup version and migrate/default-select it
with a new Flyway migration. Do not edit the historical `default@1`
definition in place.

## Test Fixture Note

MockK can throw `ClassCastException` when a mocked value-class property
returns a raw primitive. Tests should construct real `AgentSetupId`,
`AgentSetupVersion`, `WorkspaceId`, and related value objects in stubs.

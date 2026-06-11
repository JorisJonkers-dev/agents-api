# agents-api ↔ agents-ui OpenAPI contract

This service emits its OpenAPI 3.1 spec via springdoc at
`/api/v1/api-docs`. The spec is committed to
`services/agents-api/openapi.json` and consumed by
`services/agents-ui` through `openapi-typescript`-generated
`src/api/generated.ts`. CI fails any PR that lets the committed spec,
the live springdoc output, and the generated TypeScript drift apart.

## Pieces

- `services/agents-api/openapi.json` — pinned spec (committed).
- `services/agents-ui/src/api/generated.ts` — pinned TS types
  (committed; produced by `openapi-typescript` v7.13.0 plus the
  banner script).
- `OpenApiSpecExportTest` (integration tier, tag
  `contract-export`) — boots the full Spring context and dumps the
  spec to the committed path.
- Gradle task `:services:agents-api:exportOpenApiSpec` —
  runs only the tagged test; the default `integrationTest` task
  excludes the tag.
- npm scripts in `services/agents-ui/package.json`:
  - `contract:generate` — regenerate types from the committed spec.
  - `contract:check` — regenerate to `/tmp` and `diff -u` against
    the committed copy; non-zero exit on drift.
- `.github/workflows/contract-validate.yml` — runs both gates on
  every PR that touches agents-api, agents-ui, vue-common,
  published commons modules, or shared build config.

## Regenerate after an API change

```bash
./gradlew :services:agents-api:exportOpenApiSpec
pnpm --filter @extratoast/agents-ui contract:generate
git add services/agents-api/openapi.json \
        services/agents-ui/src/api/generated.ts
```

Commit the two files alongside the controller / DTO change in the
same PR.

## What CI failures look like

- **`openapi.json` drift.** The Gradle export task in
  `contract-validate.yml` overwrites the committed file with the live
  springdoc output, then `git diff --exit-code` flags the change.
  Resolve by running `./gradlew :services:agents-api:exportOpenApiSpec`
  locally and committing the result.
- **`generated.ts` drift.** `pnpm contract:check` regenerates the TS
  output to `/tmp` and `diff -u`s it against the committed copy. The
  CI log shows the patch. Resolve by running
  `pnpm --filter @extratoast/agents-ui contract:generate` and
  committing the new `src/api/generated.ts`.

## Migration status (PR H)

The repositories feature is the canary consumer:
`services/agents-ui/src/features/repositories/types/index.ts` now
derives `Repository`, `CreateRepositoryInput`, and
`AttachDeployKeyInput` from `components['schemas']` in
`@/api/generated`. `RepositoryDetail` + `AttachedProject` stay
hand-rolled until `GET /api/v1/repositories/{id}` returns a typed
response body (the controller currently emits `Map<String, Any>`).
Further feature directories migrate in follow-up PRs once their DTOs
ship with concrete response types.

# AGENTS.md — @ahoo-wang/cosec-react

<!-- This file provides coding agents with context about this package. -->

## Build & Run Commands

```bash
# Build this package (needs cosec-fetcher built: packages import each other through dist)
pnpm --filter @ahoo-wang/cosec-react build

# Run tests (and the API report)
pnpm --filter @ahoo-wang/cosec-react test

# Run a single test file
pnpm --filter @ahoo-wang/cosec-react exec vitest run test/cosec/RouteGuard.test.tsx

# Lint, type-check (the sources, then the tests)
pnpm --filter @ahoo-wang/cosec-react lint
pnpm --filter @ahoo-wang/cosec-react typecheck

# Rewrite the API report after an intended change of a public signature
pnpm --filter @ahoo-wang/cosec-react exec pnpm test:api -u

# Clean
pnpm --filter @ahoo-wang/cosec-react clean
```

## Testing

- Vitest with `globals: true`, jsdom and `@vitest/coverage-v8`; `@testing-library/react`
- Test files: `*.test.ts` / `*.test.tsx` in `test/cosec/` (mirroring `src/cosec/`)
- Run with `--coverage` flag by default; `pnpm test` also checks `test/api/cosec-react.api.md`

## Project Structure

```
src/
  index.ts                     — Barrel export
  cosec/
    useSecurity.ts             — The security state of a TokenStorage as React state (signIn, signOut, currentUser)
    SecurityContext.tsx        — SecurityProvider, useSecurityContext
    RouteGuard.tsx             — Renders children for a signed-in user, else the fallback
    RefreshableRouteGuard.tsx  — The same for a session a JwtTokenManager refreshes first
    index.ts
  internal/
    useLatest.ts               — A ref with the latest value (not exported)
    useStoredToken.ts          — A TokenStorage's pair as React state, over useSyncExternalStore (not exported)
test/
  cosec/                       — The tests, one file per source file; sessionRaces and renderPurity hold the races
  internal/                    — The tests of the two internal hooks
  api/cosec-react.api.md       — The API report (api-extractor)
```

The code is the CoSec hooks of `@ahoo-wang/fetcher-react` 6.1.0 (fetcher commit
`a214281647ef3471e5e5474670b3c09444e91ff2`), moved here without a change of behaviour. It builds with the React
compiler (`vite.config.ts`: `@vitejs/plugin-react` and `@rolldown/plugin-babel`), as there.

## Dependencies

- `@ahoo-wang/cosec-fetcher` (peer, `workspace:~`) — `TokenStorage`, `JwtTokenManager`, `CompositeToken`
- nothing else: `src/internal/` holds the two small hooks that were borrowed from `@ahoo-wang/fetcher-react` (`useLatest`, `useStoredToken` over `useSyncExternalStore`), not exported. No shared state with another package: a package of this workspace must not bring `@ahoo-wang/fetcher-react` or `@ahoo-wang/fetcher-cosec` (`scripts/package-check.mjs` fails when the consumer's tree holds the latter)
- `react` (peer, 19)

## Code Style

- TypeScript strict mode, the React compiler's lint rules (`eslint-plugin-react-compiler`, `react-hooks`)
- `pnpm lint` is `eslint .`: zero warnings. Keep it so.
- Apache 2.0 license headers
- Prettier: single quotes, trailing commas, semicolons, 80 char width

## Git Workflow

- Conventional commits: `feat(react):`, `fix(react):`, `test(react):`
- The version is the one of `gradle.properties`; `TypeScriptWorkspaceTest` keeps them equal

## Boundaries

- ✅ Writing new tests; a new hook that reads only the token storage
- ⚠️ A change of a public signature: it changes a line of `test/api/cosec-react.api.md`, and after GA `!` in the PR title
- ⚠️ Anything that decides in the browser what the server decides: the guards are a convenience of the interface
- 🚫 A hook that sends a request of its own: requests belong to `@ahoo-wang/cosec-fetcher`
- 🚫 Importing from `@ahoo-wang/fetcher-cosec`, the package `cosec-fetcher` replaces

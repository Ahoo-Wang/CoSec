# AGENTS.md — @ahoo-wang/fetcher-cosec

<!-- This file provides coding agents with context about this package. -->

## Build & Run Commands

```bash
# Build this package
pnpm --filter @ahoo-wang/fetcher-cosec build

# Run tests
pnpm --filter @ahoo-wang/fetcher-cosec test

# Run a single test file
pnpm --filter @ahoo-wang/fetcher-cosec exec vitest run test/cosecConfigurer.test.ts

# Lint
pnpm --filter @ahoo-wang/fetcher-cosec lint

# Clean
pnpm --filter @ahoo-wang/fetcher-cosec clean
```

## Testing

- Vitest with `globals: true` and `@vitest/coverage-v8`
- Test files: `*.test.ts` in a `test/` directory at the package root (mirroring `src/`)
- Run with `--coverage` flag by default

## Project Structure

```
src/
  cosecConfigurer.ts                        — Main configuration entry point
  cosecRequestInterceptor.ts                — Core CoSec request interceptor
  authorizationRequestInterceptor.ts        — Adds Authorization header to requests
  authorizationResponseInterceptor.ts       — Handles 401/403 responses
  unauthorizedErrorInterceptor.ts           — 401 Unauthorized error handling
  forbiddenErrorInterceptor.ts              — 403 Forbidden error handling
  tokenRefresher.ts                         — Token refresh logic
  tokenStorage.ts                           — Token storage abstraction
  jwtToken.ts                               — JWT token parsing and validation
  jwtTokenManager.ts                        — JWT token lifecycle management
  jwts.ts                                   — JWT utility functions
  deviceIdStorage.ts                        — Device ID generation and storage
  idGenerator.ts                            — Unique ID generation
  spaceIdProvider.ts                        — Multi-tenant space ID provider
  resourceAttributionRequestInterceptor.ts  — Resource attribution interceptor
  requestTrust.ts                           — Which request URLs may carry credentials (isTrusted, sameOriginTrust)
  types.ts                                  — Type definitions
  constants.ts                              — Shared attribute/name/order constants (re-exported from their original modules)
  errors.ts                                 — RefreshTokenError, RefreshUnavailableError, RefreshSessionChangedError (re-exported from jwtTokenManager); the first two carry the CoSec problem
  problem.ts                                — CoSec 8.0 problem+json: CoSecProblemType, readCoSecProblem, getCoSecProblem, retryAfterSeconds (resolveCoSecProblem is internal)
  refreshAttempt.ts                         — The time of the first unresolved refresh attempt of a pair, stored next to the pair (internal)
  refreshSession.ts                         — Token-session checks shared by the interceptors (internal)
  refreshLock.ts                            — Cross-tab refresh Web Lock (internal)
  storageEventBus.ts                        — Default cross-tab event bus of the CoSec storages (internal)
  index.ts                                  — Barrel export
  stories/                                  — Storybook stories
```

### Key Concepts

- **CoSecConfigurer**: Main entry point that wires up all interceptors onto a Fetcher instance
- **JWT Token Management**: Parse, validate, refresh, and store JWT tokens
- **Authorization Interceptors**: Automatically attach Bearer tokens to requests; with `isTrusted` only trusted absolute URLs receive the token and CoSec headers (default: every request)
- **Error Handling**: reads the CoSec 8.0 problem type of a refusal. `token-expired` (and a 401 with no CoSec problem) triggers token refresh + retry; `unauthorized`, `token-revoked`, `token-invalid` are not refreshed (the last two remove the stored pair); `forbidden` goes to `onForbidden`, `scope-rejected` to `onScopeRejected`; 429/503/500/400 never touch the session
- **Refresh under rotation**: only a 401 from the refresh route ends the session. An attempt whose outcome is unknown is retried with the same pair only inside `rotationLeeway` of the first attempt (CoSec 8.0 `IDN-16`); the time is stored with the pair and never slides. After the window one attempt per call, which cannot be made safe. Read the comment of `JwtTokenManager.sendRefresh` before changing it.
- **Device ID**: Persistent device identification for security tracking
- **Multi-tenant**: Space ID provider for tenant-scoped authentication. No `CoSec-Space-Id` goes on the refresh call or on a request with no token (an anonymous request that declares a space is `401`, SCP-3)
- **App**: CoSec 8.0 reads the app from the token's `aud`; `appId` is deprecated and no `CoSec-App-Id` is sent

## Dependencies

- `@ahoo-wang/fetcher` — core HTTP client
- `@ahoo-wang/fetcher-eventbus` — for auth state change events
- `@ahoo-wang/fetcher-storage` — for token and device ID persistence

## Code Style

- TypeScript strict mode
- Apache 2.0 license headers
- Prettier: single quotes, trailing commas, semicolons, 80 char width

## Git Workflow

- Conventional commits: `feat(cosec):`, `fix(cosec):`, `test(cosec):`
- Version synced via `pnpm update-version`

## Boundaries

- ✅ Adding new interceptor types for auth flows
- ✅ Adding new token storage strategies
- ✅ Writing new tests
- ⚠️ Changing token refresh logic — affects all authenticated API calls
- ⚠️ Modifying JWT parsing — security-sensitive, test thoroughly
- 🚫 Breaking CoSecConfigurer API
- 🚫 Changing 401/403 error handling contract — used by react RouteGuard (callbacks receive the exchange, and the problem as a second argument only when there is one)
- 🚫 Sending a refresh again after its `rotationLeeway` window without saying so in the docs: it revokes the session if the first attempt was recorded
- 🚫 Removing token refresh retry logic without replacement

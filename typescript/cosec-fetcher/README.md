# `@ahoo-wang/cosec-fetcher`

CoSec request attribution and optional JWT authentication for Fetcher. Use it
when the server contract expects CoSec device, request, space, or Bearer-token
behavior.

**Which CoSec it speaks.** CoSec 8.0 only. It is the client of CoSec's wire
contract ([Building a client](https://cosec.ahoo.me/integration/clients)),
versioned and released with CoSec. A CoSec 7 server needs `@ahoo-wang/fetcher-cosec`
6.x. Moving from that package: [Upgrading](#upgrading-from-ahoo-wangfetcher-cosec-6x).

## Install

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventbus \
  @ahoo-wang/fetcher-storage @ahoo-wang/cosec-fetcher
```

Peer dependencies: `fetcher`, `fetcher-eventbus`, and `fetcher-storage`.

Requires Node `^22.12.0 || ^24.0.0 || >=26.0.0`, the Node versions nanoid 6 supports, from the first 22 release where `require()` of an ES module works: nanoid is ES modules only, and the CommonJS entry (`require`) loads it that way.

## Example

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { CoSecConfigurer, sameOriginTrust } from '@ahoo-wang/cosec-fetcher';

const api = new Fetcher({ baseURL: 'https://api.example.com' });

const cosec = new CoSecConfigurer({
  isTrusted: sameOriginTrust,
  onUnauthorized: () => window.location.assign('/login'),
  onForbidden: async () => console.error('Access denied'),
});

cosec.applyTo(api);
```

This minimal configuration adds attribution headers without authentication.
Provide a `TokenRefresher` to enable token storage, Bearer injection, and
automatic refresh. Keep refresh requests on a separate unconfigured Fetcher.

`isTrusted: sameOriginTrust` keeps the token and the CoSec headers, device ID
included, on the origin of the `baseURL` and the page. Without `isTrusted`, a
request to an absolute URL on any origin receives the access token.

## Core capabilities

- Device, request, space, tenant, and owner attribution.
- JWT token parsing, persistence, sign-in, sign-out, and current-user access.
- Deduplicated automatic refresh with recursive-refresh protection, kept
  inside CoSec 8.0's `rotation-leeway` (a refresh whose answer was lost is
  retried with the same pair only while the server still answers it).
- CoSec 8.0 problem types (`application/problem+json`): a 401 is refreshed
  only for `token-expired`; `token-revoked` and `token-invalid` end the
  session; a `429` never signs the user out; `scope-rejected` has its own
  callback.
- Configurable 401, 403 and `scope-rejected` callbacks that receive the problem.
- Per-origin credential trust with `isTrusted` / `sameOriginTrust`.
- Individual interceptors for applications with a custom pipeline.

Never log, embed, or commit real tokens.

## What the CoSec 8.0 application must allow

CoSec ships no login or refresh endpoint; the application's must:

- let an **anonymous caller** reach the refresh route: the client sends the
  access token that expired, which CoSec reads as anonymous (otherwise the
  user is signed out at the first expiry);
- answer a refresh it refuses with **`401`** (only a `401` ends the session;
  `429`, `5xx` and network errors keep it) and `503` when it cannot decide;
- be configured with a CORS filter before CoSec if the client is cross-origin,
  allowing `CoSec-Device-Id`, `CoSec-Space-Id`, `CoSec-Request-Id` and
  `Authorization`, and exposing `CoSec-Request-Id`, `RateLimit-*` and
  `Retry-After`.

Set `rotationLeeway` to at most the server's `cosec.jwt.session.rotation-leeway`
(30 seconds by default). Tabs of one session must share one storage and one
lock: `localStorage` with Web Locks, or another storage declared with
`new TokenStorage({ sharedAcrossTabs: true })`. With `sessionStorage` or a
per-tab in-memory storage, refresh in one place only. The
[recipe](https://fetcher.ahoo.me/guides/integrations/cosec) has the details.

## Upgrading from `@ahoo-wang/fetcher-cosec` 6.x

- The package is `@ahoo-wang/cosec-fetcher`: change `package.json` and every import.
  The React hooks moved to `@ahoo-wang/cosec-react`.
- `appId` is deprecated and ignored: CoSec 8.0 does not read `CoSec-App-Id`,
  the app is the token's `aud`. `CoSecHeaders.APP_ID` is removed.
- `AuthorizeResult` and `AuthorizeResults` are removed: nothing in CoSec 8.0
  sends them.
- Only a `401` from the refresh route ends the session; a `400`, `403`, `404`
  or `429` now keeps it (`RefreshUnavailableError`). Pass `isRefreshRejected`
  if your refresh route refuses with another status.
- `token-revoked` and `token-invalid` are not refreshed any more, and the
  stored pair is removed.
- A `403 scope-rejected` goes to `onScopeRejected` (to `onForbidden` when
  there is none). The callbacks' second argument is the CoSec problem.
- The refresh request is cut short after 10 seconds (`timeout` option of
  `CoSecTokenRefresher`).

## Documentation

- [Building a client](https://cosec.ahoo.me/integration/clients): what a client may rely on
- [Fetcher](https://fetcher.ahoo.me), the HTTP client it is built on

[中文](./README.zh-CN.md) · [License](../../LICENSE)

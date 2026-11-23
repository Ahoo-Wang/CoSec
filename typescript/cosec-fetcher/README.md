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
  @ahoo-wang/fetcher-storage @ahoo-wang/cosec-fetcher@beta
```

Until CoSec 8.0 is released, the `latest` tag of this package points at a `0.0.0`
placeholder that is marked deprecated, so an install without a tag gets the
placeholder and a deprecation notice, not the client. Install the tag of the CoSec release you run: `@beta` for `8.0.0-beta.n` (as above), `@rc` for a release candidate, no tag once 8.0 is released; or an exact version, which stays right through all of them. A tag does not move past its line: once release candidates are out, `@beta` is still the last beta, which does not match an RC server. It is published with each
CoSec release, at the version of CoSec.

Peer dependencies: `fetcher`, `fetcher-eventbus`, and `fetcher-storage`.

Requires Node `^22.12.0 || ^24.0.0 || >=26.0.0`, the Node versions nanoid 6 supports, from the first 22 release where `require()` of an ES module works: nanoid is ES modules only, and the CommonJS entry (`require`) loads it that way.

## Example

```ts
// cosec.ts
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  CoSecConfigurer,
  CoSecTokenRefresher,
  CoSecTokenRevoker,
  type CompositeToken,
} from '@ahoo-wang/cosec-fetcher';

const baseURL = 'https://api.example.com';

// The application's Fetcher: the one that carries the token.
export const api = new Fetcher({ baseURL });
// A second, unconfigured Fetcher for refresh and logout: it never carries the CoSec headers.
const bare = new Fetcher({ baseURL });

export const cosec = new CoSecConfigurer({
  tokenRefresher: new CoSecTokenRefresher({
    fetcher: bare,
    endpoint: '/auth/refresh',
  }),
  tokenRevoker: new CoSecTokenRevoker({
    fetcher: bare,
    endpoint: '/auth/logout',
  }),
  onUnauthorized: () => window.location.assign('/login'),
  onForbidden: async () => console.error('Access denied'),
});
cosec.applyTo(api);

// Sign in: the login route is the application's. It answers the pair
// (`accessToken`, `refreshToken`), which goes into the TokenStorage.
export async function login(username: string, password: string): Promise<void> {
  const response = await fetch(`${baseURL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) throw new Error('Sign-in failed');
  cosec.tokenStorage.signIn((await response.json()) as CompositeToken);
}

// Log out: ends the session at the server, then forgets the pair here.
export const logout = () => cosec.tokenManager!.logout();
```

Every part is needed: the `TokenRefresher` turns on token storage, Bearer injection and automatic refresh; the
`TokenRevoker` makes `logout()` end the session at the server; `login` is the only place a pair enters the
`TokenStorage` (`cosec.tokenStorage.signIn`). Keep refresh and logout on the separate unconfigured `Fetcher`, as above.
Without a `TokenRefresher` the configurer only adds attribution headers.

The token, the CoSec headers (device ID included) and the tenant and owner that
fill path variables go only to the origin of an absolute `baseURL` and to the
page's origin: `isTrusted` defaults to `sameOriginTrust`. A request to an
absolute URL on any other origin (a pagination link, a download URL, a
callback) is sent bare, and its `401` starts no refresh and ends no session.
Name more origins with your own predicate, which then decides alone for
absolute URLs: `isTrusted: (url, exchange) => sameOriginTrust(url, exchange) ||
new URL(url).origin === 'https://files.example.com'`. In Node (no page) an
absolute `baseURL` is the only trusted origin; with a relative one, an
absolute URL is sent bare.

**A refresh endpoint on another origin than the `baseURL`** (an auth service of
its own). On a `Fetcher` of its own, as recommended above, nothing changes: it
never carried the CoSec headers, and the pair is the body. On the configured
`Fetcher` with an absolute endpoint on another origin, the refresh request now
goes without `Authorization` (the expired access token), `CoSec-Device-Id`,
`CoSec-Request-Id` and `CoSec-Space-Id`, and it still renews the session: the
pair is in the body, and CoSec reads the expired token as anonymous, so the
route does not need the header. What is lost is the device and request id on
that call. To keep them, name the origin: `isTrusted: (url, exchange) =>
sameOriginTrust(url, exchange) || new URL(url).origin ===
'https://auth.example.net'`. On the `baseURL`, the refresh carries the expired
`Authorization`, `CoSec-Device-Id` and `CoSec-Request-Id` as before, and no
space.

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
- Per-origin credential trust: same origin by default (`sameOriginTrust`), your own `isTrusted` to change it.
- Individual interceptors for applications with a custom pipeline.

Never log, embed, or commit real tokens.

## Logging out

Call `logout()` for the user's "Log out": it ends the session at the server
(CoSec's logout route, `cosec.authentication.login.logout.path`) and removes
the pair here. Call `tokenStorage.signOut()` when the session is already known
to be over, or when the application ends it through a logout endpoint of its
own: it only forgets the pair here, and the session stays refreshable at the
server until it ends there.

```ts
import { logout } from './cosec'; // from the example above

await logout();
```

A refresh in flight is waited for, and the session is ended with the pair it
stored. `logout()` resolves when the server ended the session (`204`) or
answered `401` (it was not usable any more), and removes the pair here on every
outcome. A `LogoutUnconfirmedError` (`503`, `500`, a network failure, no
`tokenRevoker`) asks the application to tell the user that the server may still
hold the session, until it ends there, at the latest when its refresh token
expires. It carries no token: `problem` and `status` say what the server
answered.

## What the CoSec 8.0 application must allow

The starter serves the refresh route when `cosec.authentication.login.refresh.path`
is set, with the answers this client relies on ([Building a client](https://cosec.ahoo.me/integration/clients#what-your-application-serves));
the login is the application's. The application must:

- let an **anonymous caller** reach the refresh route: the client sends the
  access token that expired, which CoSec reads as anonymous (otherwise the
  user is signed out at the first expiry);
- if it writes its own refresh route, answer a refresh it refuses with
  **`401`** (only a `401` ends the session; `429`, `5xx` and network errors
  keep it) and `503` when it cannot decide, as the served route does;
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

## Reporting errors

The errors of this package that a refresh or a logout raises
(`RefreshTokenError`, `RefreshUnavailableError`, `RefreshSessionChangedError`,
`LogoutUnconfirmedError`) carry no token: the request of a refresh or a logout
has the pair as its body, so its error is not kept. Their `cause` is a plain
`Error` with the name and message of that error and of each of its causes, and
`problem` and `status` say what the server answered.

The error of an application's own request (a `FetcherError`, an
`ExchangeError` around one of the errors above) still carries the `exchange` of
that request: its headers, so the access token in `Authorization`, and its
body. An application that sends errors to a reporter (Sentry, a log pipeline)
scrubs `exchange` first, or reports only `name`, `message` and `problem`.

## Upgrading from `@ahoo-wang/fetcher-cosec` 6.x

- **Credentials go only to trusted origins by default.** Without `isTrusted`,
  6.x sent the token, `CoSec-Device-Id`, `CoSec-Request-Id` and `CoSec-Space-Id`
  to every absolute URL, and filled `{tenantId}` and `{ownerId}` from the token.
  Now only the origin of an absolute `baseURL` and the page's origin get them.
  What breaks: a call to a third origin that relied on the token (a second API
  on another host, a CDN that checks it) now arrives without it, as a `401` of
  that origin. Migrate with a predicate that names the origin:
  `isTrusted: (url, exchange) => sameOriginTrust(url, exchange) ||
new URL(url).origin === 'https://files.example.com'`. Code that really sent
  to every origin writes `isTrusted: () => true` and owns that decision. Calls
  with an absolute URL in Node without an absolute `baseURL` are sent bare too.

- The package is `@ahoo-wang/cosec-fetcher`: change `package.json` and every import.
  The React hooks moved to `@ahoo-wang/cosec-react`.
- `appId` is removed (`CoSecConfig.appId`, `CoSecRequestOptions.appId`,
  `AppIdCapable`): CoSec 8.0 does not read `CoSec-App-Id`, the app is the
  token's `aud`. `CoSecHeaders.APP_ID` is removed. `CoSecOptions`, which only
  combined options nothing used, is removed too, and so is
  `TokenStorage.setCompositeToken`: use `signIn`.
- `RefreshTokenError` and `RefreshUnavailableError` no longer carry the token
  pair (`token` property, and the first constructor parameter): an error
  reporter that serializes an error's own properties would have sent both
  tokens. Their `cause` is typed `unknown`; so are `JwtPayload`'s index
  signature and `CoSecJwtPayload.attributes`: read a claim outside the typed
  ones with a type guard.
- `JwtTokenManager.refreshBlocked` and `refreshInProgress` are private.
- `parseJwtPayload` logs nothing when it cannot parse a token (it answers
  `null`).
- The two `Symbol.for` keys the package shares between instances on one page
  are `@ahoo-wang/cosec-fetcher/shared-token-serializers` and
  `@ahoo-wang/cosec-fetcher/notification-guard-refresher`. During a migration
  both packages can be on one page: they no longer share a registry or a
  marker.
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

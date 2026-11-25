---
name: cosec-client
description: Connect a browser or React application to a CoSec 8.0 application with @ahoo-wang/cosec-fetcher and @ahoo-wang/cosec-react - install with the npm tag of the server's release, wire the token pair (storage, Bearer token, refresh under rotation, logout, tabs), handle each problem type CoSec answers (401 kinds, 403, scope-rejected, 429, 503, 400), switch tenant or app with the token pair, guard routes with the React hooks, move from @ahoo-wang/fetcher-cosec, and test the client code. Do not use for the server side (cosec-integration) or for diagnosing a server's decision (cosec-troubleshoot).
---

# CoSec TypeScript Client

`@ahoo-wang/cosec-fetcher` is the client of a CoSec 8.0 application, built on `@ahoo-wang/fetcher`;
`@ahoo-wang/cosec-react` is its React hooks. They are released with CoSec, at CoSec's version, and speak the contract in
[`contract/`](https://github.com/Ahoo-Wang/CoSec/tree/main/contract). What a client may rely on, in any language:
https://cosec.ahoo.me/integration/clients. This skill adds the TypeScript wiring; the page wins where they differ.

Wire the smallest client that fits the application: keep its HTTP layer, router and state management, and never
log, embed or commit a real token.

## Inspect first

- Which CoSec the server runs (`8.0.0-beta.n`, `8.0.0-RCn`, `8.0.0`): it picks the npm tag. A CoSec 7 server needs the
  old `@ahoo-wang/fetcher-cosec` and the hooks of `@ahoo-wang/fetcher-react`; nothing here works against it.
- Whether the application already has `@ahoo-wang/fetcher-cosec` or `@ahoo-wang/fetcher-react`'s security hooks
  (then follow [Coming from `@ahoo-wang/fetcher-cosec`](#coming-from-ahoo-wangfetcher-cosec)).
- The paths the server serves: its login (always the application's own), the refresh and logout (the starter serves
  them at `cosec.authentication.login.refresh.path` and `.logout.path`, or the application does), and a tenant switch
  if it has one.
- Whether the API is on the page's origin, on another origin (CORS), and whether the client calls other origins
  (a CDN, a second API) that must or must not get the token.
- Whether requests carry a space (`CoSec-Space-Id`), and React or not.

## Install

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventbus @ahoo-wang/fetcher-storage \
  @ahoo-wang/cosec-fetcher@beta
pnpm add @ahoo-wang/cosec-react@beta   # React applications, with react 19
```

- **The tag is the server's release line.** `@beta` for `8.0.0-beta.n`, `@rc` for a release candidate, no tag once
  8.0 is released; or the exact version the server runs, which is right through all of them. A tag stays on its line:
  once release candidates are out, `@beta` still installs the last beta.
- **No tag before GA installs nothing usable.** Until 8.0 is released, `latest` of both packages is `0.0.0`, a
  placeholder marked deprecated ("Placeholder. Install the beta: ..."), with no entry point: the install warns and the
  import does not resolve.
- **Peer dependencies.** `cosec-fetcher` needs `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-eventbus` and
  `@ahoo-wang/fetcher-storage` (`^6.1.1`); `cosec-react` needs `react` (`^19.3.1`) and `@ahoo-wang/cosec-fetcher`.
  Keep exactly one copy of `@ahoo-wang/cosec-fetcher`: the hooks and the client share one `TokenStorage` instance.
  Neither package depends on `@ahoo-wang/fetcher-cosec` or `@ahoo-wang/fetcher-react`.
- Node `^22.12.0 || ^24.0.0 || >=26.0.0` to build or test (nanoid 6, ES modules only).

## Wire the client

One module creates the client; everything else imports it:

```ts
// cosec.ts
import { Fetcher, ResultExtractors } from '@ahoo-wang/fetcher';
import {
  CoSecConfigurer,
  CoSecTenantSwitcher,
  CoSecTokenRefresher,
  CoSecTokenRevoker,
  type CompositeToken,
} from '@ahoo-wang/cosec-fetcher';

const baseURL = 'https://api.example.com';

export const api = new Fetcher({ baseURL }); // the application's requests: they carry the token
const bare = new Fetcher({ baseURL }); // refresh and logout: the pair is their body, no CoSec header

export const cosec = new CoSecConfigurer({
  tokenRefresher: new CoSecTokenRefresher({ fetcher: bare, endpoint: '/auth/refresh' }),
  tokenRevoker: new CoSecTokenRevoker({ fetcher: bare, endpoint: '/auth/logout' }),
  tenantSwitcher: new CoSecTenantSwitcher({ fetcher: api }), // only if the application has a switch route
  onUnauthorized: () => window.location.assign('/login'),
  onForbidden: async () => console.warn('Not allowed'),
});
cosec.applyTo(api);

// The login is the application's route; it answers the pair.
export async function login(username: string, password: string): Promise<void> {
  const pair = await bare.post<CompositeToken>('/auth/login', {
    body: { username, password },
  }, { resultExtractor: ResultExtractors.Json });
  cosec.tokenStorage.signIn(pair);
}

export const logout = () => cosec.tokenManager!.logout();
```

What each part does:

- **`tokenRefresher` turns authentication on**: without it the configurer only adds `CoSec-Device-Id`,
  `CoSec-Request-Id`, `CoSec-Space-Id` and the path variables, and `tokenManager` is `undefined`. With it, every
  request to a trusted origin gets `Authorization: Bearer <access token>`, and the pair refreshes itself.
- **`tokenStorage.signIn(pair)` is the only way a pair enters the client.** It keeps `accessToken` and `refreshToken`
  and nothing else of what the login answered. `signOut()` forgets it here only.
- **The pair lives in `localStorage` by default** (`DEFAULT_COSEC_TOKEN_KEY`, `cosec-token`): every tab of the origin
  shares it and takes turns to refresh it (a Web Lock), and a change in one tab reaches the others. Any script on the
  origin can read it, refresh token included: keep foreign script off the origin (Content-Security-Policy, trusted
  dependencies). `new TokenStorage({ storage: sessionStorage })`, or memory, hides it from other tabs but not from a
  script on the page, and then each tab holds a copy that must not refresh alongside another (see
  [Refresh](#refresh)). Another storage the tabs really share is declared with `sharedAcrossTabs: true`, and only then.
  Without `navigator.locks` (a page that is not a secure context) tabs cannot take turns.
- **Credentials go to trusted origins only.** `isTrusted` defaults to `sameOriginTrust`: the origin of an absolute
  `baseURL` and the page's origin get the token, the three `CoSec-*` headers and `{tenantId}`/`{ownerId}` filled
  from the token; any other absolute URL is sent bare, and its `401` starts no refresh and ends no session. To trust
  one more origin:
  `isTrusted: (url, exchange) => sameOriginTrust(url, exchange) || new URL(url).origin === 'https://files.example.com'`.
- **The token pair goes to trusted endpoints only.** A refresh, a logout or a tenant switch whose endpoint, resolved
  against the `Fetcher`'s `baseURL`, the client does not trust is refused before anything is sent (a `FetcherError`
  saying so): never build a switch endpoint from input that can name another origin. A refresher or revoker on a
  `Fetcher` of its own trusts its `baseURL`; on the configured `Fetcher`, an endpoint on another origin needs that origin
  in `isTrusted`, which is asked about the `POST` that is sent. A refresh, a switch or a logout follows no redirect: a
  route that answers one fails the request (the session is kept), since a `307` or `308` would re-send the pair.
- **Refresh and logout on the bare `Fetcher`**, as above. On the configured one they work too (they mark their own
  request so it never refreshes itself and never carries a space), but an endpoint on another origin is then refused
  unless `isTrusted` names it.
- `{tenantId}` and `{ownerId}` in a URL template are filled from the access token's `tenantId` and `sub`
  (`ResourceAttributionRequestInterceptor`) when the request does not give them.
- `CoSec-Device-Id` is generated once and kept (`DeviceIdStorage`, `localStorage`); `CoSec-Request-Id` is new for each
  request. Log the `CoSec-Request-Id` of a response: it is the problem's `requestId` and finds the request in the audit.

The server must let an anonymous caller `POST` the refresh route (the expired access token reads as anonymous), and a
cross-origin client needs a `CorsConfigurationSource` bean on the server that allows `Authorization`,
`CoSec-Device-Id`, `CoSec-Request-Id`, `CoSec-Space-Id` and exposes `CoSec-Request-Id`, `RateLimit-Limit`,
`RateLimit-Remaining`, `RateLimit-Reset`, `Retry-After`. Both are server changes: `$cosec-integration`.

## Refresh

A session is a family of pairs, and only the newest refreshes; a pair presented again after the server's
`cosec.jwt.session.rotation-leeway` (30 seconds by default) ends the whole session. The client keeps to that:

- **When.** Before a request, when the stored access token has expired by the device's clock (minus
  `TokenStorage`'s `earlyPeriod`, in seconds, `0` by default) and the refresh token has not. After a response, on a
  `401 token-expired`, or a `401` whose body is not a CoSec problem: once, then the request is sent again once
  (`AUTHORIZATION_RESPONSE_MAX_RETRY = 1`). A retried request that is `401` again goes to `onUnauthorized`.
- **One at a time.** Concurrent requests share one refresh; tabs on one shared storage take turns and the one that
  waited adopts the pair the other stored.
- **A failed refresh.** Only a `401` from the refresh route ends the session: the pair is removed and the request
  rejects with an `ExchangeError` whose `exchange.error` is a `RefreshTokenError` (`problem` says why:
  `token-expired`, the session's lifetime is over or the route is closed to anonymous callers; `token-revoked`;
  `token-invalid`). Anything else keeps the pair: `RefreshUnavailableError` with the `problem` and `status`
  answered. Pass `isRefreshRejected` only if the application's own refresh route refuses a dead pair with another
  status.
- **Never a refresh the server refused.** A `401` is final; a `400`, `403` or `429` answer from the pipeline
  moved nothing and is not retried, and after a `429` the route is not called again for that pair
  until its `Retry-After` (at most 60 seconds). A refresh whose outcome is unknown (a timeout, a network error, a
  `5xx`) is retried with the same pair after `refreshRetryDelays` (`[500, 2000]` ms) and by later requests, but only
  while less than `rotationLeeway` minus 5 seconds has passed since it was first sent; after that it is not sent again.
  Set `rotationLeeway` (ms, default `30000`) to at most the server's leeway, which the client cannot read, and keep
  `CoSecTokenRefresher`'s `timeout` (`10000` ms) well below it.
- **A body that can be read once** (a stream) cannot be sent again after a refresh: the request fails with
  `NonReplayableRequestError` and the session is refreshed; send it again with a new body.
- A custom `TokenRefresher` on the configured `Fetcher` must mark its request with
  `IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY` (`attributes: new Map([[IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true]])`), or the
  refresh refreshes itself. Prefer `CoSecTokenRefresher`.

## Logout

`cosec.tokenManager!.logout()` is the user's "Log out": it waits for a refresh in flight, sends the stored pair to the
`tokenRevoker`, then removes the pair here whatever the server answered. It resolves on `204` (and on `401`: the
session was over anyway). It rejects with `LogoutUnconfirmedError` (`problem`, `status`) on `503`, `500`, a network
error, a timeout, or without a `tokenRevoker`: tell the user the server may hold the session until it ends there, at
the latest when the refresh token expires. Use `tokenStorage.signOut()` alone only for a session known to be over.

## What CoSec answers

A refusal is `application/problem+json` with `type` (CoSec's problems URI, ending in the slug), `title`, `status`,
`code`, `requestId` (and `traceId` when traced). Decide on the slug (`getCoSecProblem(error)?.slug`, compared with
`CoSecProblemType`), never on `title`, and give a slug you do not know the default of its status class: types are
added in minor releases. What the packages do, and what is left to the application:

| Status | Slug | The client does | The application does |
|---|---|---|---|
| 400 | `invalid-request` | Nothing; the session is kept, nothing refreshes | Treat it as a client bug (a `CoSec-*` header sent twice with different values, a path the pipeline refuses); do not resend it |
| 401 | `unauthorized` | Does not refresh; keeps the pair; `onUnauthorized(exchange, problem)` | Sign in; if it holds a token, check that the request sent it (an untrusted origin gets none) |
| 401 | `token-expired` | Refreshes once and resends once | Nothing; `onUnauthorized` only if the refresh or the retry fails |
| 401 | `token-revoked`, `token-invalid` | Does not refresh; removes the stored pair (if it is still the one sent); `onUnauthorized` once | Sign in again |
| 403 | `forbidden` | `onForbidden(exchange, problem)` | Show that the user may not; do not retry |
| 403 | `scope-rejected` | `onScopeRejected(exchange, problem)`, else `onForbidden`; keeps the stored space | Let the user pick a space where the principal has a role, or get a token for the host's app ([switch](#switch-tenant-or-app)); never fall back to another space by itself |
| 429 | `too-many-requests` | Nothing; never a sign-out, on the refresh route too | Wait `retryAfterSeconds(error.exchange.response)` seconds (`Retry-After`, equal to `RateLimit-Reset`), then retry |
| 503 | `unavailable` | Nothing; the session is kept | Retry later with a back-off; a `503` has no `Retry-After` |
| 500 | `internal-error` | Nothing | Do not retry blindly; report the `requestId` |

```ts
import { HttpStatusValidationError } from '@ahoo-wang/fetcher';
import { CoSecProblemType, getCoSecProblem, retryAfterSeconds } from '@ahoo-wang/cosec-fetcher';

try {
  await api.get('/orders');
} catch (error) {
  const problem = getCoSecProblem(error);
  if (problem?.slug === CoSecProblemType.TOO_MANY_REQUESTS && error instanceof HttpStatusValidationError) {
    scheduleRetry(retryAfterSeconds(error.exchange.response) ?? 1);
  }
}
```

The callbacks are called only when configured (`onUnauthorized`, `onForbidden`, `onScopeRejected`). A host that
renders its own problems and replaces `type` makes every refusal unknown to the client: a `401` is refreshed once
whatever it was, and the callbacks get no problem. `readCoSecProblem(response)` reads a problem from a plain `Response`.

## Spaces

`CoSec-Space-Id` comes from a `SpaceIdProvider`; the default sends none. `DefaultSpaceIdProvider` sends the space kept
in a `SpaceIdStorage` on the requests its predicate names:

```ts
import { DefaultSpaceIdProvider, SpaceIdStorage } from '@ahoo-wang/cosec-fetcher';

export const spaces = new SpaceIdStorage(); // spaces.set('warehouse-7') when the user picks one
// in the CoSecConfigurer options:
//   spaceIdProvider: new DefaultSpaceIdProvider({
//     spacedResourcePredicate: { test: exchange => exchange.request.url.startsWith('/inventory') },
//     spaceIdStorage: spaces,
//   }),
```

The space is never sent on a request without a token (the server would answer `401`, `SCP-3`), nor on the refresh.

## Switch tenant or app

A token's tenant and app (`aud`) are fixed by whoever issued it; the client never sends a header for them. To work in
another tenant or app, call the application's switch endpoint (its own route, over `SwitchTenantAuthentication`)
through `JwtTokenManager.switchTenant`. The configurer's `CoSecTenantSwitcher` ([wiring](#wire-the-client)) is over the configured `Fetcher`, not the bare one:
the switch is a request the pipeline permits, with the `CoSec-Device-Id` a `TenantSwitch` may check.

```ts
import { TenantSwitchError } from '@ahoo-wang/cosec-fetcher';
import { cosec } from './cosec';

export async function switchTo(tenant: string, app: string): Promise<void> {
  try {
    await cosec.tokenManager!.switchTenant({
      endpoint: `/auth/select/${encodeURIComponent(tenant)}/${encodeURIComponent(app)}`, // the application's route
    });
  } catch (error) {
    if (error instanceof TenantSwitchError && !error.sessionEnded) {
      // the application's own refusal (error.status, its body), or 503 / no answer: the session is unchanged
    }
    throw error;
  }
}
```

What it does (`JwtTokenManager.switchTenant`, from the code and its tests against the fake host and `test/client-host`):

- **Presents the current generation.** It waits for a refresh in flight, refreshes an expired access token first, then
  sends under the lock refreshes take (in-process, and the same Web Lock across tabs), reading the stored pair again
  once it holds the lock. The pair goes in the body (with `body`'s members of the application's own, if given) and its
  access token in `Authorization`: always one pair. No space is sent. A refresh asked meanwhile waits, and refreshes
  nothing of the old session (a request made with it fails with `RefreshSessionChangedError` rather than being sent
  again in the new tenant).
- **Stores the answer as one write**, which other tabs on a shared storage read as a new sign-in, so the React hooks and
  `currentUser` follow. If the stored session changed meanwhile (a sign-out or sign-in elsewhere), nothing is stored:
  `RefreshSessionChangedError`.
- **`401 token-expired` is refreshed once.** The route is reached with the access token, so the pipeline answers
  `token-expired` when the access token expired at the server though the device clock (behind) says otherwise, and the
  route answers it when the session's lifetime is over. The pair is refreshed once and the switch sent again (wire:
  switch, refresh, switch). A session that is over refuses the refresh, which ends it as any refused refresh does:
  `RefreshTokenError`, the pair removed, `onUnauthorized` once, no second switch. A second `token-expired` after a
  refresh that succeeded keeps the (refreshed) pair: `TenantSwitchError` with `sessionEnded: false`.
- **Any other refused pair ends the session**: a `401` from the route (`token-revoked`: a logout, a pair presented
  again, or the end of the login's session for a session a switch started, `IDN-36`; `token-invalid`) removes the
  stored pair if it is still the one sent, calls `onUnauthorized` once, and rejects with `TenantSwitchError`
  (`sessionEnded: true`, `problem`, `status`).
- **Anything else keeps the session**: `TenantSwitchError` with `sessionEnded: false` for the application's own refusal
  (its `TenantSwitch` threw: a `403` of its own, which also reaches `onForbidden`), a `503` (the revocations or the
  session store), a network error or timeout (no `status`). Nothing is retried. Calling again is safe: the server checks
  the pair as a refresh does but does not rotate it, so the same current pair switches again; a switch whose answer was
  lost started a session nobody holds, which ends with the login's.
- A refresh it needs first can fail as any refresh does (`RefreshTokenError`, `RefreshUnavailableError`).

The application's route must answer a refused pair `401` and its own refusal with any other status (the client reads a
`401` as the end of the session), as `test/client-host`'s `/auth/switch/{tenant}` does. A switched session keeps the
login time, so it ends when the login's would.

## React

```tsx
import { RefreshableRouteGuard, SecurityProvider, useSecurityContext } from '@ahoo-wang/cosec-react';
import { cosec } from './cosec';

export function App() {
  return (
    <SecurityProvider
      tokenStorage={cosec.tokenStorage}
      tokenManager={cosec.tokenManager}
      onSignOut={() => window.location.assign('/login')}
    >
      <RefreshableRouteGuard tokenManager={cosec.tokenManager!} fallback={<p>Please sign in</p>}>
        <Profile />
      </RefreshableRouteGuard>
    </SecurityProvider>
  );
}

function Profile() {
  const { currentUser, logout } = useSecurityContext();
  const onLogout = () => logout().catch(() => alert('Signed out here; the server did not confirm.'));
  return <button onClick={onLogout}>Log out {currentUser.sub}</button>;
}
```

| Need | Use | It gives |
|---|---|---|
| The signed-in state for the whole tree | `SecurityProvider` (`tokenStorage`, optional `tokenManager`, `onSignIn`, `onSignOut`) and `useSecurityContext()` | `UseSecurityReturn` (below); `useSecurityContext` throws outside a provider. `SecurityContext` is the raw context |
| The same in one component, without a provider | `useSecurity(tokenStorage, { tokenManager, onSignIn, onSignOut })` | `UseSecurityReturn` |
| Show a page only to a signed-in user | `RouteGuard` (`fallback`, `onUnauthorized`), inside a provider | Its children when `authenticated`, else `fallback`; `onUnauthorized` runs after the render commits, once each time the user becomes (or starts) not signed in |
| The same, refreshing an expired access token first | `RefreshableRouteGuard` (`tokenManager`, `fallback`, `refreshing`) | Children when `authenticated`; while a refresh runs `refreshing` (default `<p>Refreshing...</p>`); `fallback` when there is nothing to refresh or it failed (logged with `console.error`, by name and message) |

`UseSecurityReturn`: `currentUser` (the access token's claims, even once expired, or `ANONYMOUS_USER` with `sub`
`anonymous`), `authenticated` (a pair is stored and its access token has not expired), `signIn(pairOrAsyncProvider)`
(stores it and calls `onSignIn`; a sign-out or newer sign-in meanwhile wins), `signOut()` (here only, at once, then
`onSignOut`), `logout()` (`JwtTokenManager.logout()` of the `tokenManager` option; the pair is gone either way,
`onSignOut` fires unless a newer sign-in replaced the session, and it rejects with `LogoutUnconfirmedError` on an
unconfirmed logout or without a `tokenManager`). A `tokenManager` that manages another `TokenStorage` than the hook's
makes the hook throw. A component switches tenant with `cosec.tokenManager!.switchTenant(...)`
([Switch tenant or app](#switch-tenant-or-app)); the hooks follow the stored pair, so `currentUser` changes with it.
The guards are a convenience of the interface: CoSec decides on the server. Read claims as
[the claims table](https://cosec.ahoo.me/integration/clients#the-claims-a-client-may-read) says: `roles` and `policies`
may be absent for a user who has roles, so never hide or show features from them.

## Coming from `@ahoo-wang/fetcher-cosec`

The old package and the security hooks of `@ahoo-wang/fetcher-react` speak CoSec 7's contract and do not work against
an 8.0 server. Replace the package in `package.json` and every import: `@ahoo-wang/fetcher-cosec` becomes
`@ahoo-wang/cosec-fetcher`, and `SecurityProvider`, `useSecurity`, `useSecurityContext`, `RouteGuard`,
`RefreshableRouteGuard`, `ANONYMOUS_USER` come from `@ahoo-wang/cosec-react` (same behaviour as in
`@ahoo-wang/fetcher-react` 6.1.0; the guards return `ReactNode`; `CompositeTokenProvider` is exported). What behaves
differently:

| Before | Now |
|---|---|
| The token and the CoSec headers went to every absolute URL | Only to trusted origins (`isTrusted`, default `sameOriginTrust`); name another origin in your predicate, or write `() => true` and own that |
| An `appId` option sent an app header | No `appId`, no header (`CoSecConfig.appId`, `CoSecRequestOptions.appId`, `AppIdCapable`, `CoSecHeaders.APP_ID` are gone): the app is the token's `aud` |
| Every `401` refreshed | Only `token-expired` or a `401` without a CoSec body; `token-revoked`/`token-invalid` remove the pair; `unauthorized` keeps it |
| Any failed refresh ended the session | Only a `401` of the refresh route; `isRefreshRejected` for another status; the refresh times out after 10 s |
| Every `403` went to `onForbidden` | `scope-rejected` goes to `onScopeRejected` first; callbacks receive the problem as their second argument |
| `RefreshTokenError`/`RefreshUnavailableError` carried the pair | No error carries a token; their `cause` is a plain `Error` by name and message. Your own request's error still holds its `exchange` (with `Authorization`): scrub it before an error reporter |
| `TokenStorage.setCompositeToken`, `CoSecOptions`, `AuthorizeResult(s)` | `signIn`; removed; removed |
| `JwtTokenManager.refreshBlocked`/`refreshInProgress` | Private |

Both packages can be on one page during the move: they share no registry (`Symbol.for` keys under
`@ahoo-wang/cosec-fetcher/...`). The server side of the move (`logout.path`, an anonymous refresh route, CORS) is
`$cosec-integration`.

## Testing client code

What a test needs comes from the packages and their peers, with no browser storage:

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { CoSecConfigurer, CoSecTokenRefresher, DeviceIdStorage, TokenStorage } from '@ahoo-wang/cosec-fetcher';

const store = (key: string) => ({
  key,
  storage: new InMemoryStorage(),
  eventBus: new SerialTypedEventBus<never>(key),
});

export function testClient(fetch: typeof globalThis.fetch) {
  const tokens = new TokenStorage(store('test-tokens'));
  const devices = new DeviceIdStorage(store('test-devices'));
  const api = new Fetcher({ baseURL: 'https://api.test', fetch });
  const cosec = new CoSecConfigurer({
    tokenStorage: tokens,
    deviceIdStorage: devices,
    refreshRetryDelays: [0, 0],
    tokenRefresher: new CoSecTokenRefresher({ fetcher: api, endpoint: '/auth/refresh' }),
  });
  cosec.applyTo(api);
  return { api, cosec, destroy: () => [tokens, devices].forEach(storage => storage.destroy()) };
}
```

- **Answer from the contract, not from memory.** A stub `fetch` answers a refusal as
  `contract/problems/<slug>.json` states it (`new Response(JSON.stringify(file.body), { status: file.status, headers:
  file.headers })`), the refresh and logout as `contract/routes/*.json` do, and a pair with exactly the members of
  `contract/token/composite-token.json`. Take the files from the CoSec tag the server runs. The client never verifies
  a signature, so a test token is an unsigned JWT with the claims of `contract/token/claims.json` (`exp`, `iat`,
  `jti` and `sub` are always there).
- **Decide a case from the wire, never from timing**: record `<path> <status>` of each call the stub answers, and
  assert the order (one `/auth/refresh` between two requests, none on a `429`). For expiry, sign in with a pair whose
  access token already expired, or move only `Date` (`vi.useFakeTimers({ toFake: ['Date'] })`); never wait for a token.
- **Against a real CoSec**: the packages' own host tests run against `test/client-host` in the CoSec repository, the
  starter over WebFlux with CoCache in memory, a login, the served refresh and logout routes, and switches that end a
  session, issue an expired access token, or take the revocations down (`503`). It is the model for an application's
  own end-to-end tests against a test instance of its server: what a unit test assumes about CoSec's answers is
  asserted there too, and where they disagree, the host is right. In a CoSec checkout: `cd typescript && pnpm test:host`.
- Destroy the storages after each test (`destroy()`), and give each test its own keys.

## Never

- Never keep the pair in a place several holders refresh independently (per-tab memory or `sessionStorage` with
  several tabs refreshing): the second refresh of one pair ends the session (`token-revoked`).
- Never retry a refresh after `rotationLeeway`, never treat a `429` or `503` as a sign-out, never send the login answer
  back as it came (the routes accept exactly the pair).
- Never decide what the user may do from `roles`, `policies` or a route guard: the server decides.

Use `$cosec-troubleshoot` when the server's answers are not what the client expects, and `$cosec-integration` for
the server's refresh and logout routes, the anonymous statement on the refresh route and CORS.

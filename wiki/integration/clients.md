---
title: "Building a client"
description: "What a client of a CoSec 8.0 application can rely on: the endpoints that are yours, what to do on each problem type, how to refresh under rotation, the claims it may read, the CoSec-* headers, and CORS."
---

# Building a client

For someone writing a client in any language (a browser app, a mobile app, a service) of an application that runs
CoSec. It says what the server does and what the client must do about it; what the application serves is its own
([Authentication](./authentication.md)), and every status and body is defined once, in
[Web integration](./web.md#responses-and-status-codes). Nothing here is specific to one client library.

The facts a client parses are also files, in
[`contract/`](https://github.com/Ahoo-Wang/CoSec/tree/main/contract) (`PIP-25`): every problem type's status, headers
and body, the `CoSec-*` headers and the request id's grammar, the token pair's body, the claims of CoSec's tokens, and
what the served refresh and logout routes answer. The server's tests check its answers against them as parsed JSON, so a
client's own tests can use them as the answers to expect. A claim the file marks `optional` may be missing from a
token, and a client that reads it handles that: the table of [the claims a client may read](#the-claims-a-client-may-read)
says what its absence means. `@ahoo-wang/cosec-fetcher`'s tests run against the same files, and a check of the docs holds that table to
`token/claims.json`.

## What your application serves

CoSec serves no login and no tenant switch: those are the application's, written over CoSec's classes
([Logging in](./authentication.md#logging-in)), with the path, the body and the status of each failure its own. The
refresh and the logout the starter serves itself when their paths are set; an application that sets none writes them as
it writes its login.

| Endpoint | Who serves it | In and out |
|---|---|---|
| Login | The application: `TokenCompositeAuthentication.authenticateAsToken(credentials)` | Your credentials in; a `CompositeToken` out |
| Refresh | The starter at `cosec.authentication.login.refresh.path` (`IDN-23`); unset, the application, over `RefreshTokenAuthentication`. The starter's route needs a `MembershipRefresh` bean or `cosec.jwt.session.memberships-on-refresh=from-token` (`STA-8`) | `{"accessToken": "...", "refreshToken": "..."}` in; a `CompositeToken` out |
| Logout | The starter at `cosec.authentication.login.logout.path` (`IDN-24`); unset, the application: `TokenRevoker.revoke(accessToken)`, or `revoke(compositeToken)` once the access token has expired | The served route: the pair in, `204` out; the session ends on the server |
| Tenant or app switch | The application: `SwitchTenantAuthentication`, from a live session's pair | The current token in the `Authorization` header, and the pair (`accessToken`, `refreshToken`) with your credentials class in the body; a `CompositeToken` out |

A `CompositeToken` is written as `{"accessToken":"<jwt>","refreshToken":"<jwt>"}`, nothing else
(`CompositeTokenJsonTest` keeps it byte for byte). An application that adds fields to its answer is not breaking
CoSec, and a client must ignore the ones it does not know. A client sends back exactly the pair, never the answer as it
came: the refresh and logout routes answer `400 invalid-request` to any other body. `cosec-fetcher` keeps only
`accessToken` and `refreshToken` of what `signIn` is given.

**What the served refresh route answers.** Every answer carries `Cache-Control: no-store`.

| Request, or what went wrong | Status | Body |
|---|---|---|
| `POST`, `Content-Type: application/json`, the pair and nothing else, at most 32 KiB | `200` | The `CompositeToken` |
| Another member, a missing or empty value, another media type, a larger body, not JSON | `400` | `invalid-request` |
| The session's lifetime is over (`cosec.jwt.session.lifetime`), or the refresh token has expired | `401` | `token-expired`: sign in again |
| The session ended: a logout, or a pair presented again (`IDN-16`, `IDN-18`) | `401` | `token-revoked`: sign in again |
| The pair is not valid, belongs to no session, or is not one pair | `401` | `token-invalid`: sign in again |
| The revocations or the store that keeps the session cannot answer: nothing was issued (`IDN-14`, `IDN-19`) | `503` | `unavailable`: keep the pair and try again |
| Any other error | `500` | `internal-error`: keep the pair |

A body that is not the token is a problem as the pipeline writes it ([problem types](#on-this-problem-type-a-client-does-this)):
it carries the request id of the `CoSec-Request-Id` header, no trace id, and nothing else of the request. The served
logout answers `204` when the session is now ended and when it already was, so a logout whose answer was lost can be
sent again, and otherwise the same `400`, `401`, `503` and `500`.

The route takes its credentials from the body and sets no cookie, so a page of another site cannot make a browser send
them for it: it is not a cross-site request forgery target. A gateway does not serve it, and its startup fails when a
path is set there: the service that issues sessions serves the route, behind the gateway, which passes it on like any
other route its documents permit.

An application that writes its own refresh route answers as this table does: `RefreshTokenAuthentication` throws, and
CoSec registers no exception handler for a route it does not serve, so an exception the handler does not map is
whatever the framework answers.

| It throws | Answer |
|---|---|
| `TokenExpiredException`, `TokenRevokedException`, any other `TokenVerificationException` (`RefreshTokenMismatchException`, `DelegationUnsupportedException`) | `401`, with the type of the row above |
| `RevocationUnavailableException`, `SessionStoreUnavailableException` (the store that keeps the session) | `503`: nothing was issued |
| Anything else | `500` |

**Login and refresh must be reachable by an anonymous caller.** A refresh carries the access token that expired, and
an expired token is a rejected one: the request is evaluated as anonymous (`PIP-6`). If no statement lets an anonymous
caller reach the refresh route, the refresh itself is `401 token-expired` and the client cannot renew. A client that
sends no `Authorization` header on the refresh does not change that: it is anonymous either way. The served routes are
no exception (`STA-4`): nothing is exempted from the filter, and when a document refuses one the starter logs a `WARN`
naming its property and shows it on the `cosecLoginRoutes` health indicator.

<<< @/examples/clients/documents/platform.json [documents/platform.json: the statement of the routes a client needs]

The scenarios run against it: a refresh that carries an expired access token is decided as an anonymous caller, and is
permitted where the statement exists, and denied where it does not.

<<< @/examples/clients/scenarios/refresh-is-permitted-to-an-expired-access-token.json [scenarios/refresh-is-permitted-to-an-expired-access-token.json]

<<< @/examples/clients/scenarios-closed/refresh-is-denied-without-a-statement.json [scenarios-closed/refresh-is-denied-without-a-statement.json, the same request without the statement]

## On this problem type, a client does this

A refused request has a body of `application/problem+json`, always the same five members in the same order
(`type`, `title`, `status`, `code`, `requestId`, then `traceId` when tracing captured one), and nothing else: no policy,
no statement, no cause ([Responses and status codes](./web.md#responses-and-status-codes)). Decide on `code` (or the
last segment of `type`), never on `title`. The set of types grows in minor releases (RFC 0010 Q6), so every client needs
a default for a type it does not know: act on its status as the table's status class does.

| `code` | Status | It means | The client |
|---|---|---|---|
| `INVALID_REQUEST` | `400` | The request cannot be read as one: a `CoSec-*` parameter sent twice with different values, a path the pipeline refuses, or (behind a gateway) an identity it cannot read | Is a bug of the client. Do not retry the same request, and never refresh |
| `UNAUTHORIZED` | `401` | No credential, or an anonymous caller that declared a space (`SCP-3`) | Signs in. If it holds a token, checks that the request sent it |
| `TOKEN_EXPIRED` | `401` | The access token has expired | Refreshes once with the pair, then retries the request once. A refresh that is refused (`401`) means sign in |
| `TOKEN_REVOKED` | `401` | The token, or every token of its principal, was revoked | Signs in. A refresh cannot help: the session ended (`IDN-18`, `IDN-22`) |
| `TOKEN_INVALID` | `401` | The credential is not accepted: a bad signature or claims, a refresh token where an access token belongs (`IDN-13`), a delegation token (`IDN-4`), a principal its provider no longer knows (`IDN-3`) | Signs in. A refresh cannot help |
| `FORBIDDEN` | `403` | The caller is authenticated and the decision is deny | Does not retry. Refreshing rarely helps: a refresh carries the old claims unless the application re-reads the user. It shows that the user may not |
| `SCOPE_REJECTED` | `403` | The app is not the host's, or the space is one the principal has no role in, or begins with `(` (`SCP-1`, `SCP-2`, `SCP-9`) | Does not retry, and does not choose another space on its own: a different space, or the default one, would move the user's next writes somewhere they did not mean. It tells the application, which lets the user pick a space the principal has a role in, or sign in for the app the host serves. A space the server rejected is not sent again until the application has chosen |
| `TOO_MANY_REQUESTS` | `429` | The request was permitted and a [rate limit rule](../policies/rate-limiting.md) has no permit left | Waits `Retry-After` seconds and retries. Never treats it as a sign-out, on the refresh route too |
| `UNAVAILABLE` | `503` | No decision could be made (`fail closed`) | Retries later with a back-off and keeps the session. A `503` never carries `Retry-After` |
| `INTERNAL_ERROR` | `500` | CoSec failed in a way it does not model | Does not retry blindly; reports the `requestId` |

Headers that come with them:

- `CoSec-Request-Id` is on every response, a `400` included: the client's own value when it is a plain id, else a
  generated one, and equal to the problem's `requestId`. Log it; it finds the request in the audit and the trace.
- `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset` come with a `429`, and with a permitted response
  that spent a permit; `Retry-After` only with a `429`, and it equals `RateLimit-Reset`
  ([Responses and headers](../policies/rate-limiting.md#responses-and-headers)).
- CoSec sets no `WWW-Authenticate`.

**A rejected token is not an error.** A public route answers `200` to a request whose token has expired, and says
nothing about it: the caller was anonymous. A client that wants to know its token is about to expire reads `exp` and
refreshes before the request; it does not wait for a `401`.

## A host that renders its own problems

A host may render the body of CoSec's refusals in its own problem catalog (`ProblemRenderer`, `PIP-34`,
[Problem renderer](../architecture/extending.md#problem-renderer)). The status and the headers stay CoSec's; the body is
the host's. **This client recognizes a refusal by its `type` alone** (`https://cosec.ahoo.me/problems/<slug>`), and
reads `code` only from a body whose `type` is CoSec's. On a host that replaces `type`, every refusal is one it does not
know:

- a `401` is refreshed once, whatever it was: an expired token is still refreshed, but a revoked or invalid one does not
  end the session, and a request without a credential is refreshed for nothing;
- the `onUnauthorized` and `onForbidden` callbacks receive no problem, so the application cannot tell an expired token
  from a revoked one, or `FORBIDDEN` from `SCOPE_REJECTED`.

So that it is not blind: the host keeps CoSec's `type` and `code` and adds its own catalog's identifiers as extension
members beside them (the sample in [Problem renderer](../architecture/extending.md#problem-renderer) does). A host that
must replace `type` gives its clients its own handling of these statuses instead of this client's.

## Refreshing under rotation

A session is a family of pairs, and only the newest pair refreshes ([Sessions](./authentication.md#sessions)). What
that asks of a client:

- **One refresh per pair at a time, for every copy of it.** Tabs, windows and processes that share a stored pair share
  one lock and read the store again when they get it. Two copies of one session that each refresh are a copy used
  after a refresh: the session ends (`IDN-16`). That is the case with a pair kept per tab (in memory, in a store a tab
  does not share), or per process.
- **Store the new pair before using it, and drop the old one.**
- **A refresh whose answer never arrived** (a timeout, a lost connection) may have been recorded or not, and the client
  cannot tell. Presenting the same pair again is answered with a pair of the current generation if it comes within
  `cosec.jwt.session.rotation-leeway` of the move (30 seconds by default, an operator's setting a client cannot read),
  and moves nothing. What follows: the client's own retry window is configuration, and must be at most the server's
  value. A late retry that finds the refresh already recorded is, to the server, a pair presented again, which an
  operator may see in the audit. After the leeway, presenting it ends the whole session, on every tab and device. So retry within the leeway, or
  not at all: a late retry is harmless only if the first request never arrived. The worst case of not retrying is a
  sign-in, which is also the worst case of retrying late.
- **Read the status of the refresh.** `401` means the session is over: discard the pair and sign in. `503`, a network
  error or a timeout is no verdict: keep the pair. Any other `4xx` (`429`, `400`) is no verdict on the session either:
  a client that signs the user out on every `4xx` ends sessions a rate limit only paused.
- **The refresh token's `exp` is the end of the road.** No token of a pair outlives its session
  (`cosec.jwt.session.lifetime`, `IDN-17`), so a client that reads the refresh token's `exp` knows when a refresh
  cannot work and skips the call.

**Where the pair is kept, and who can read it.** `cosec-fetcher` keeps the pair in `localStorage` by default, so any
script that runs on the page's origin, a dependency or an injected one, can read the refresh token and renew the
session until its lifetime ends. That is what keeps one refresh for every tab of a session. `sessionStorage` or memory
(`new TokenStorage({ storage: ... })`) keep the pair from other tabs but not from a script on the page, and cost the
shared refresh: each tab then holds a copy of its own and must not refresh it alongside another (above). What protects
the pair is keeping foreign script off the origin: a Content-Security-Policy, and dependencies you trust.

## The claims a client may read

The client does not verify a token: only the application can, so what it reads decides nothing, it only shows. These
are the claims of an access token ([Tokens](./authentication.md#tokens)):

| Claim | A client may use it for | When it is missing |
|---|---|---|
| `exp` | Deciding when to refresh. A token without `exp` is rejected, so it is always there | |
| `iat` | When the token was issued | |
| `sub` | The principal id, to show or to fill a path | |
| `tenantId` | The tenant, to show or to fill a path | The principal is in the default tenant; a token from a first sign-in step names none |
| `aud` | The app the token is for, one string | The token is for no app: it reads no app document |
| `attributes` | Display data the application put there | Empty |
| `roles`, `policies` | Nothing about what the user may do: they are left out when empty and **when `cosec.membership` is `"provider"`**, and a change takes effect at the next login or refresh | Does not mean "no roles" |
| `jti` | An id | |
| `sid`, `auth_time` | The session's id and the time its user signed in (seconds) | Not a session: the pair is never refreshed |
| `cosec.generation`, `cosec.token`, `cosec.membership` | Nothing: a client must not depend on them | |

The refresh token is a JWT as well. Read its `exp`; its other claims name the session and its access token, and a
client has no use for them.

## The CoSec request headers

All of them are headers only: a parameter in the URL is not read, since a URL reaches access logs and `Referer`
headers ([App and space](./web.md#app-and-space)).

| Header | What to send | What the server does |
|---|---|---|
| `Authorization` | `Bearer <access token>` | The token. A refresh call may send the expired one; it is read as anonymous |
| `CoSec-Space-Id` | One value, only for a request in a space, and only a space the signed-in principal has a role in. **Never on login or refresh, nor on any request that carries no token**: the caller is anonymous there, and a declared space is `401` (`SCP-3`) | Anonymous with a space: `401` (`SCP-3`). A space without a role, or beginning with `(`: `403 scope-rejected` (`SCP-1`, `SCP-9`). Empty or blank means the default space |
| `CoSec-Device-Id` | One value, stable per installation | Recorded for an authenticated request; read by a policy or the blacklist when one asks |
| `CoSec-Request-Id` | One value, 1 to 64 characters of `A-Z a-z 0-9 . _ -`, new per request | Kept and echoed; any other value is replaced by a generated one |

A parameter sent twice with different values, or a blank one beside a non-blank one, is `400 invalid-request`
(`SCP-4`); blank means ASCII whitespace only. No header declares an app, and a client that sends one for it changes nothing: the app is the token's (below).

**Send them only to the origins the application is built against**: the API's own origin and the page's. A pagination
link, a download URL or a callback that points elsewhere gets none of the four, and its `401` is no reason to refresh or
to end the session: it says nothing about this application's session. A tenant or owner read from the token does not go
into the URL of such an origin either. `@ahoo-wang/cosec-fetcher` does this by default (`isTrusted`, which defaults to
`sameOriginTrust`); an application that must send them to another origin names it in its own predicate.

## The app comes from the token

A client does not choose its app: the app is the token's `aud`, set by whoever issued it. A host that serves one app
(`cosec.authorization.scope.app-id`, or a gateway route's `cosec-app-id`) refuses a token for another app, or for none,
with `403 scope-rejected` (`SCP-2`, `SCP-11`). To work in another app, the client calls the application's switch
endpoint and stores the pair it answers; it never rewrites a header ([Apps](./authentication.md#apps)).

## Logout and switching

- **Logout is a server call, then a local one.** Forgetting the pair on the client leaves it refreshable until the session
  ends. Call the logout route, the starter's or the application's (it ends the session and revokes the access token, `IDN-18`; `cosec-fetcher`'s `JwtTokenManager.logout()` does both steps), then
  drop the pair. Once the access token has expired, the endpoint needs the pair.
  Call the server's logout for the user's "Log out"; forget the pair alone only when the session is already known to be
  over. A logout the server did not confirm (`503`, `500`, no answer) still removes the pair on the client: the user
  asked to leave the device. The client then tells the user that the server may hold the session until it ends there,
  at the latest when its refresh token expires. A logout waits for a refresh in flight and ends the session with the
  pair that refresh stored, since the pair it rotated away would be refused. In React, `cosec-react`'s `logout` (with the
  `tokenManager` option) is that logout, and `signOut` the local step alone.
- **A switch answers a new pair.** Present the current pair, as a refresh does (the access token in `Authorization`
  too), store the pair that answers, and drop the old one. A switch issues only from a live session's current pair (the
  one just replaced, within the rotation leeway) and keeps its login time (`IDN-17`, `IDN-22`); it does not sign the
  user in again. Refresh first if another tab refreshed long ago: an older pair presented to switch revokes the session,
  as a refresh of it would. Path parameters a client fills from `tenantId` change with the new token.
  A switch does not rotate the pair it is presented with, so presenting it again after an answer that never arrived is
  safe: the lost answer started a session nobody holds, which ends with the login's (`IDN-36`). Read a refused switch as
  a refused refresh: `401` means the session is over, `503` keeps the pair, and anything else is the application's own
  refusal, which leaves the session as it was. `cosec-fetcher`'s `JwtTokenManager.switchTenant({ endpoint })` (with a
  `CoSecTenantSwitcher` of the configured `Fetcher`) does all of this: it presents the stored pair under the lock refreshes
  take, refreshing an expired access token first, removes the pair on a `401`, and stores the answer as one write.

## CORS

A browser client needs the application to configure CORS: CoSec sets no `Access-Control-*` header itself, and the starter
registers a CORS filter before the pipeline only when the application declares a `CorsConfigurationSource` bean.
[CORS](./web.md#cors) says what happens to a preflight on each stack (with the bean it is answered and never decided,
without it it is decided as `http:OPTIONS` for an anonymous caller), what `@CrossOrigin` and `addCorsMappings` still do,
and the request headers to allow and the response headers to expose: `CoSec-Request-Id`, `RateLimit-Limit`,
`RateLimit-Remaining`, `RateLimit-Reset` and `Retry-After`, which a client reads. A client that sees a network error where
it expects a `401` is looking at a refusal without CORS headers.

## Coming from `@ahoo-wang/fetcher-cosec`

The client of a CoSec 8 application is `@ahoo-wang/cosec-fetcher`, and its React hooks are `@ahoo-wang/cosec-react`.
They replace `@ahoo-wang/fetcher-cosec` and the CoSec hooks of `@ahoo-wang/fetcher-react`, are released with CoSec at its
version, and speak the contract of this page. The old package speaks the contract of CoSec 7 and does not work against an 8.0
server. Install them with their peer dependencies (the fetcher packages):

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventbus @ahoo-wang/fetcher-storage \
  @ahoo-wang/cosec-fetcher@beta @ahoo-wang/cosec-react@beta
```

Until 8.0 is released, the `latest` tag of both packages points at a `0.0.0` placeholder marked deprecated, so an install
without a tag gets the placeholder and a deprecation notice, not the client. Install the tag of the CoSec release you run: `@beta` for `8.0.0-beta.n` (as above), `@rc` for a release candidate, no tag once 8.0 is released; or an exact version, which stays right through all of them. A tag does not move past its line: once release candidates are out, `@beta` is still the last beta, which does not match an RC server. Change the package name in `package.json` and in every import, and the
hooks' imports from `@ahoo-wang/fetcher-react` to `@ahoo-wang/cosec-react`. What behaves differently:

| What the old package did | What `cosec-fetcher` does |
|---|---|
| Sent the token, `CoSec-Device-Id`, `CoSec-Request-Id` and `CoSec-Space-Id` to every absolute URL, and filled `{tenantId}` and `{ownerId}` in a URL from the token | Sends them only to the origin of an absolute `baseURL` and to the page's origin (`isTrusted`, default `sameOriginTrust`). A call to another origin that relied on the token now arrives without it: name that origin in your own `isTrusted` predicate. A call to another origin that gets a `401` starts no refresh and ends no session |
| Sent `CoSec-App-Id`, from `appId` options | Has no `appId` option and sends no `CoSec-App-Id`: the server does not read it, and the app is the token's ([The app comes from the token](#the-app-comes-from-the-token)) |
| Refreshed on a `401` | Reads the problem type of a `401`. `token-expired`, or a `401` without a body CoSec wrote, is refreshed once and the request retried. `unauthorized` is not refreshed. `token-revoked` and `token-invalid` are not refreshed either, and remove the stored pair |
| Ended the session when a refresh failed, whatever the failure | Ends the session only when the refresh route answers `401`; a `400`, `403`, `404`, `429`, a `5xx` or a network failure keeps it. `isRefreshRejected` names a different refusal. The refresh request is cut short after 10 seconds |
| Handed every `403` to `onForbidden` | Hands a `403 scope-rejected` to `onScopeRejected`, and to `onForbidden` when there is none. The callbacks receive the problem as their second argument |
| Put the token pair on `RefreshTokenError` and `RefreshUnavailableError` | Puts no token on any error it raises: a refresh's or a logout's error keeps the request's error by name and message only, since that request's body is the pair. The error of your own request still carries its `exchange`, whose request holds the `Authorization` header: scrub it before it goes to an error reporter |

The package's own README lists the removed members one by one.

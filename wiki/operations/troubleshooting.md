---
title: "Troubleshooting"
description: "Find out why a request was denied, allowed, refused or failed, and why an instance does not start, starting from the symptom."
---

# Troubleshooting

For developers and operators facing an unexpected decision, a startup failure or a control-plane error. Start from the
symptom; each section says how to confirm the cause and fix it, and links to the page that holds the facts. Quoted
messages are exactly what CoSec logs or returns; `<...>` stands for the values it fills in. Every problem body's
`type` is `https://cosec.ahoo.me/problems/<slug>`, and each slug has its own section below.

## Find out what decided

The response body says only what the client can act on. The detail is in the audit event:

1. Take the `CoSec-Request-Id` response header, which is also the `requestId` of the problem body.
2. Find its audit event. The audit log writes denials, `429`s, `503`s and errors to the logger `me.ahoo.cosec.audit` at
   `WARN` and `ERROR`. Read `authorization.reasonCode` and `authorization.reason`, the deciding statement in
   `authorization.source`, the `binding` it applied through, and the `snapshotVersion` it read
   ([Audit events](./observability.md#audit-events)).
3. For every statement a decision checked and why each matched or not, explain the request through the control plane
   ([Explaining a decision](../integration/checks.md)). With `cosec-opentelemetry` the deciding statement is also on
   the `cosec.authorize` span ([Tracing](./observability.md#tracing)).

## By status code

| Status | Problem `code` | Go to |
|---|---|---|
| `400` | `INVALID_REQUEST` | [`invalid-request`](#invalid-request) |
| `401` | `UNAUTHORIZED` | [`unauthorized`](#unauthorized) |
| `401` | `TOKEN_INVALID`, `TOKEN_EXPIRED`, `TOKEN_REVOKED` | [`token-invalid`](#token-invalid), [`token-expired`](#token-expired), [`token-revoked`](#token-revoked) |
| `403` | `FORBIDDEN` | [`forbidden`](#forbidden) |
| `403` | `SCOPE_REJECTED` | [`scope-rejected`](#scope-rejected) |
| `429` | `TOO_MANY_REQUESTS` | [`too-many-requests`](#too-many-requests) |
| `503` | `UNAVAILABLE` | [`unavailable`](#unavailable) |
| `500` | `INTERNAL_ERROR` | [`internal-error`](#internal-error) |

A `5xx` whose body has no `type` under `https://cosec.ahoo.me/problems/` is not CoSec's
([a gateway error](#a-permitted-request-answers-500-with-the-gateways-own-error-body)).
An explicit and an implicit deny give the same response; the audit event tells them apart. How outcomes map to
statuses, headers and bodies is defined once, for every adapter, in [Web integration](../integration/web.md).
Control-plane responses have more statuses: [Control-plane errors](#control-plane-errors).

### `invalid-request` {#invalid-request}

The request cannot be read without ambiguity, so it is refused before authentication. The audit event has `kind`
`BAD_REQUEST`. Causes:

- the path has `.` or `..` segments, a malformed percent-escape, an empty segment before its end (`//`, often two
  URL parts joined with a `/` each), an encoded separator (`%2F`), or a backslash (`\`, `%5C`). A trailing `/` is not refused;
- the path holds an encoded `;` (`%3B`) or a control character, encoded (`%00` to `%1F`, `%7F`, in either case) or
  raw (`PIP-30`, from 8.0.0-beta.5). A client that sent such a path before now gets `400`: usually a value put into a
  path variable without care, such as a `;`-separated list, a line break or binary data. Nothing names this cause
  apart from the others: the audit record has `kind` `BAD_REQUEST` and `reasonCode` `INVALID_REQUEST`, and its
  `request.path` is the path as received, as is the access log's. Match that path against `%3B`, `%00` to `%1F` and
  `%7F`. Send the value in the query or the body, or encode its `%` again (`%253B`), which the pipeline decodes once,
  to the text `%3B`, and the application decodes again. A trailing `.` or space and `%25` are not refused;
- a `CoSec-Space-Id` or `CoSec-Device-Id` header is sent with two different values, a blank one beside
  a non-blank one included. Send each header once, or leave it out;
- a route map captures a value that would change the resource id's segments;
- a header was sent more than once with different values, and a document the request is decided on reads it, in a
  condition, a boundary or a rate limit rule, whichever the statement's effect and whether it applies to this request
  (`PIP-12`). This one is checked after authentication and the scope, once the documents are known. Send the header
  once, or with one value. A header proxies append to, such as `X-Forwarded-For`, is the usual case: two proxies that
  each add a line make every request of a view whose documents read it a `400`, even a `GET` when the statement that
  reads it covers only `DELETE`, and a platform statement does this for every tenant. Have the proxies send one
  combined line, or read the client address as `environment.ip`, which the host resolves once;
- on a service in `cosec.pipeline.mode=gateway`, a `CoSec-Identity` header that cannot be read
  ([A service behind the gateway refuses requests](#a-service-behind-the-gateway-refuses-requests)).

Fix the client ([Web integration](../integration/web.md)). On the control API, a `400` names the problem in `detail`
([Control-plane errors](#control-plane-errors)).

### `unauthorized` {#unauthorized}

The caller is anonymous, and the request was denied: no credential was sent, or no statement allows anonymous callers
to do this. A scope rejection of an anonymous caller is also `unauthorized`.

1. **Was a credential sent where CoSec reads it?** A token in the `Authorization` header, the
   `authorization` query parameter with [`cosec.pipeline.allow-query-token=true`](./configuration.md#pipeline), or the
   `authorization` cookie with [`cosec.pipeline.allow-cookie-token=true`](./configuration.md#pipeline); an API key in the
   [`cosec.pipeline.api-key-header`](./configuration.md#pipeline) header with `cosec.api-key.enabled=true`.
2. **Should anonymous callers be allowed here?** Then bind a statement that allows them; otherwise send a credential.

### `token-invalid` {#token-invalid}

The token, or the API key, was rejected, and the request, evaluated as anonymous, was denied. A rejected credential
is not an error by itself: if a statement allows anonymous callers, the request proceeds as anonymous.

- **Token:** the issuer and this service must share [`cosec.jwt.secret` and `cosec.jwt.algorithm`](./configuration.md#jwt).
  With `cosec.jwt.enabled=false` every token is rejected.
- **Claims CoSec refuses:** a `tenantId` claim that is empty or not a string (a number, `null`, an object), a `roles` or
  `policies` claim that is not an array of strings, an `attributes` claim that is not an object, or one that names
  `apiKeyId`, which CoSec sets for API keys only. Issue the tenant as a non-empty string, or no claim for the default
tenant, and
  rename the attribute ([Authentication](../integration/authentication.md#tokens)).
- **An id with an unpaired surrogate:** the token's `sub`, `tenantId`, `jti`, `roles` or `policies` holds an unpaired
  UTF-16 surrogate, which JSON writes as an escape such as `"beta\uD800"` (`IDN-10`). A Redis key holds `?` in its
  place, so the token would read the documents, memberships and revocations of `beta?`: it is refused before any of
  them is read. CoSec's own issuer refuses to sign one: login fails with `Principal [alice\uD800] holds an unpaired
  UTF-16 surrogate ...`. Fix the issuer, or the user store it reads: an id is Unicode text.
- **A tenant or app id with `/`:** the token's `tenantId` or `aud` holds `/`, such as `acme/west`, which a 7.x or
  preview issuer could sign (`SCP-13`). A tenant or app id is one path segment, so the token names neither tenant
  `acme/west` nor `acme`. Rename the tenant or app where its id is kept, then have the principal sign in again
  ([Upgrading](./upgrading.md#tenant-and-app-ids-hold-no-slash)).
- **Intermittent, at second boundaries:** a few requests fail with `token-invalid`, each in the last milliseconds of a
  second, and the log says `Token refused as not valid yet: The token's iat is later than this instance's clock`. The
  issuer's clock is ahead of this instance's by more than
  [`cosec.authentication.revocation.skew`](./configuration.md#authentication) (5 seconds, `IDN-29`), and a token stamped with the issuer's next second reaches a host still in the previous
  one. The decision event's `subject.rejected` is `TOKEN_NOT_YET_VALID`. Synchronize the clocks (NTP); raise the skew
  only for a known, bounded drift, since it also widens what a subject revocation covers.
- **Delegation token:** the token carries an `act` claim, which CoSec refuses whatever else about it is valid; the
  audit event's `reasonCode` is `DELEGATION_UNSUPPORTED` (`IDN-4`). Have the caller use a token of its own instead
  of one that acts for someone else ([Authentication](../integration/authentication.md#tokens)).
- **API key:** the key's SHA-256 is not stored, or the key is disabled or expired
  ([API keys](../integration/authentication.md#api-keys)). A request that carries an API key is judged by the key
  alone; its token is not read.
- **Unknown or deactivated principal:** the `MembershipProvider` answered `Unknown` for the principal of the key, or of
  a token marked `cosec.membership: provider`, or answered `active = false` (`IDN-3`). The decision event's
  `subject.rejected` is `API_KEY_INVALID` or `TOKEN_INVALID`, and `subject.refused` names the principal and the
  credential. Make the provider know the principal in the key's tenant, or reactivate it; with
  `CoCacheMembershipProvider`, call `invalidate` so the change applies before the cache's TTL. A provider that answers empty memberships for a principal it knows keeps the key valid
  with no roles: that is `forbidden`, not this.
- **Behind a gateway:** a `CoSec-Identity` of another version, without `auth.exp`, or issued in the future
  ([A service behind the gateway refuses requests](#a-service-behind-the-gateway-refuses-requests)).

### `token-expired` {#token-expired}

The access token's lifetime is over. Refresh it ([Authentication](../integration/authentication.md)), or check the
clocks and [`cosec.jwt.token-validity.access`](./configuration.md#jwt). Behind a gateway, the forwarded identity can
also be too old ([A service behind the gateway refuses requests](#a-service-behind-the-gateway-refuses-requests)).

A refresh refused as `token-expired`: the session's lifetime is over ([`cosec.jwt.session.lifetime`](./configuration.md#jwt),
30 days from the login). Sign in again.

**Every refresh is answered `401 token-expired` (or `unauthorized`), and clients sign out when their access token
expires.** No statement permits an anonymous `POST` of the refresh route: its request carries the access token that
expired, which CoSec reads as anonymous (`PIP-6`), so the route is refused before it runs. On the route the starter serves,
look for the `WARN` `cosec.authentication.login.refresh.path=<path>: no statement permits an anonymous POST of it` (or
`a DENY statement matches` it, or `the tenant's platform boundary does not cover it`), and for `denied since` on the
`cosecLoginRoutes` health indicator. Permit `http:POST` on the path to anyone
([Building a client](../integration/clients.md#what-your-application-serves)); the same holds for the logout route.

### `token-revoked` {#token-revoked}

The token was revoked by logout, or every token of its principal issued before a subject revocation was revoked
([Authentication](../integration/authentication.md)). Sign in again. A refresh refused as `token-revoked` also means its
session ended: by a logout, or because an older pair of the session was presented after a newer one had been issued
more than `cosec.jwt.session.rotation-leeway` (30 seconds) after it (reuse: a pair was copied, or a client kept an
old pair or retried later than that). Look for two clients sharing one session's tokens, or a client whose retry
outlasts the leeway. A token issued up to
[`cosec.authentication.revocation.skew`](./configuration.md#authentication) (5 seconds) after a subject revocation is
revoked too, so a sign-in right after one may need a few seconds. A refresh applies the same skew.

A token that still works on another instance after logout: a "not revoked" answer is cached locally for 30 seconds,
which bounds how long an instance can miss a revocation whose invalidation message was lost. During a Redis outage
the revocation check accepts tokens ([When Redis is unavailable](./storage.md#when-redis-is-unavailable)).

### `forbidden` {#forbidden}

The caller is authenticated, and the request was denied: a DENY matched, nothing allowed it, or an allow was outside
the tenant's boundary. Read `reasonCode` in the audit event (`EXPLICIT_DENY`, `IMPLICIT_DENY` or `BOUNDARY`) and the
deciding statement, then explain the request ([Find out what decided](#find-out-what-decided)).

- **`EXPLICIT_DENY`:** a deny wins over every allow ([Security model](../guide/security-model.md)). Narrow the deny or
  change the request. A capped API key is denied what its principal is denied: a DENY bound to the principal, or that
  it attaches, applies to every key of the principal, whatever the cap ([API keys](../integration/authentication.md#api-keys)).
- **`IMPLICIT_DENY`:** check the statement you expected to allow it: its action and resource (with a route map, the
  route's action and resource, not the raw path), its condition, and the binding that should apply it to this
  principal, app and space ([Policy language](../policies/language.md),
  [Role permissions](../policies/role-permissions.md)). Roles and attached policies come from the token's membership
  claims or the `MembershipProvider`, so a change applies after a new token or once the membership cache expires.
- **`BOUNDARY`:** an allow matched, but the tenant's platform boundary does not cover the request.

### `scope-rejected` {#scope-rejected}

The scope cannot be granted to this caller:

- the credential declares an app other than the host's, or none: on a gateway, the route's metadata `cosec-app-id`,
  else [`cosec.authorization.scope.app-id`](./configuration.md#authorization). Issue the token for this app (its `aud`,
  [Apps](../integration/authentication.md#apps)). A credential's app that cannot be an app id (blank, `*`, with `:`, `/` or
  a control character, or longer than 128 characters) is refused too;
- `CoSec-Space-Id` names a space where the principal has no role.

Send the host's app, or none, and a space the principal is a member of
([Trust boundaries](../guide/security-model.md#trust-boundaries)).

### `too-many-requests` {#too-many-requests}

A permit spent the last permit of a rate-limit rule. The `RateLimit-*` and `Retry-After` headers give the limit and when
to retry, and the audit `reasonCode` is `TOO_MANY_REQUESTS` ([Rate limiting](../policies/rate-limiting.md)).

- **Unrelated callers share a limit:** the rule's bucket key is the same for them.
- **The limit is N times higher than configured:** without a `ReactiveStringRedisTemplate` bean the limiter counts
  per instance ([Rate limiting](./configuration.md#rate-limiting)).
- **A rule never applies:** denied requests spend no permit, and a rule whose limiter fails is skipped
  (`Rate limiter unavailable: rule [<id>] is skipped.` at `WARN`) unless it is strict.
- **No rule applies on a Redis Cluster**, and that `WARN` carries `CROSSSLOT Keys in request don't hash to the same
  slot`: the instance runs a version whose counters span two keys without a hash tag (8.0.0-alpha.2 or earlier), so
  every call fails, and a rule with `strictFailure` makes every permitted request `503` instead. Upgrade it
  ([Upgrading](./upgrading-previews.md#from-8-0-0-alpha-2)): a bucket is now one key. A `cosec.key-prefix` with a hash tag of its own, such as
  `{cosec}`, puts every counter on one slot: it works, but one node counts for the whole deployment.

### `unavailable` {#unavailable}

No decision could be made, so the request was refused: CoSec fails closed. The audit event's `decision` is
`INDETERMINATE`, and `authorization.reason` names the cause; the metric tag `cause` groups them. A `503` never carries
`Retry-After`.

| `reason` / metric `cause` | Cause | Fix |
|---|---|---|
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`, on every request | No platform document is published: a new deployment, or a Redis that lost it. `cosec-migrate bootstrap` writes only the platform tenant's document. Until a platform document exists every request is `503`, the control API included. On a new deployment, readiness refuses traffic until the first one is installed ([Instance is not ready](#instance-is-not-ready)). Readiness latches once it accepts: a document lost later does not make it refuse again, so every request fails closed with `503`, and only the `cosecSnapshot` health indicator goes DOWN. That turns readiness DOWN only if you add `cosecSnapshot` to the readiness group, which during a Redis outage turns 503s into connection failures. | Publish one with `cosec-migrate publish` ([The first documents](./storage.md#the-first-documents)). |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`, for some tenants only | The tenant is in [`cosec.authorization.snapshot.fail-closed.tenants`](./configuration.md#snapshot), and the oldest document its requests read was last confirmed longer ago than `fail-closed.max-lag` (`Snapshot lag [<lag>] exceeds the fail-closed bound [<max>] of tenant [<tenant>].`). | Restore Redis; see [Decisions use an old revision](#decisions-use-an-old-revision). |
| `INDETERMINATE: DocumentInvalid` / `document_invalid` | A document the request needs is stored but does not compile on this instance, at the revision the decision event names. Most often a document written by a newer CoSec than the instance reading it, during a rolling upgrade (a member this version does not know; a document of another `schema` is refused when it is read, and shows as `SourceUnavailable`), or one written to the store directly rather than through the control plane; an app or tenant document can also stop compiling when a new platform revision changes the `limits` or `attributes` it compiles against. Only the requests that need that document fail closed (`503`); the client's answer is the same as for any other `503`. It is logged once per revision and inputs at `ERROR`: `Snapshot document [<doc>] revision <n> does not compile against the limits and attributes of platform revision <p>; every request that needs it fails closed (503) until a revision that compiles is published.`, with the JSON path of each error, and `... revision <n> compiles; it is decided with again.` at `INFO` when it ends. The `cosecSnapshot` health indicator counts it in `invalid` and names it in `invalidFirst`, which lists the first ten. | Finish the rolling upgrade, so that every instance reads the version that wrote it; or publish a revision that compiles through the control plane, which checks it before it publishes. |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable` | A document the request needs could not be read in time (Redis down or slow on a cold load), cannot be decoded, or disappeared from Redis after it was seen. A failed load and a lost document are logged with the document's name: `Loading snapshot document [<doc>] failed.`, `Snapshot document [<doc>] disappeared from the store; failing closed.` | Restore Redis, or publish a readable revision of the document ([When Redis is unavailable](./storage.md#when-redis-is-unavailable)). |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable` after upgrading from 8.0.0-alpha, with `401`s of revoked tokens turned `503` and the control API `500` | The data 8.0.0-alpha wrote through CoCache 6, which CoCache 8 does not read: `Cache[<name>] - key[<key>] holds content this cache cannot read (format[1], this node writes format[2]: not written in this node's format)`. It fails closed; nothing reads it as absent. | Stop every instance and move the data with the `cosec-migrate` of 8.0.0-alpha.4; 8.0.0's no longer has the command ([Upgrading](./upgrading-previews.md#data-written-through-cocache-6)). Do not delete these keys by hand, as the message suggests for a value that can be dropped: a deleted revocation accepts its token again, a deleted document leaves its tenant without its DENYs. |
| `cosec-migrate publish` answers `Refused: [<p>:meta:cocache8] marks a CoCache 6 to 8 upgrade of 8.0.0-alpha that stopped after it deleted the documents` | An 8.0.0-alpha upgrade of the CoCache 6 data stopped half-way: the documents it deleted are in its export, not in Redis. Nothing was written. | Finish it with the `cosec-migrate` of 8.0.0-alpha.4: `publish --resume`, with `--in` on the export the message names. Or check that every document the marker lists is published, then delete the key ([Upgrading](./upgrading-previews.md#from-8-0-0-alpha-4)). |
| `INDETERMINATE: MissingPolicies`, `AmbiguousPolicy` / `missing_policies` | A binding or an attachment names a policy the documents do not define, or a policy id both the tenant and the platform define. | Fix the document through the control plane. |
| `INDETERMINATE: MembershipUnavailable` / `membership` | The `MembershipProvider` failed, or took longer than the membership timeout (10 % of `cosec.pipeline.timeout`). With `CoCacheMembershipProvider`, a `MembershipLoader` that does not answer within that time fails its load (`The membership loader did not answer within ...`), and the next request tries again. | Fix the provider or its store. |
| `INDETERMINATE: MembershipUnavailable` / `membership`, for some principals only | The `MembershipProvider` answered a space, role or policy id with an unpaired UTF-16 surrogate (`The membership provider answered an id with an unpaired UTF-16 surrogate for principal [<id>].`, `IDN-10`): such an id would share a Redis key with another, so the answer is not used. | Fix the provider's store: an id is Unicode text. |
| `INDETERMINATE: AttributeUnavailable` / `attribute` | A statement needs an external attribute whose provider failed or timed out (`<provider> failed to fetch [...]` at `WARN`, at most every 10 seconds per provider; every decision carries the cause). | Fix the provider, give it a timeout of its own (`AttributeProvider.timeout`), or raise `cosec.pipeline.timeout`, of which the attribute timeout is 6 %. |
| `INDETERMINATE: AttributeUnavailable` / `attribute`, for IPv6 clients only, after upgrading | A statement reads `ext.ip2region.*` and the host has no IPv6 ip2region database: the bundled one is IPv4's, and an address no loaded database covers is unavailable, never absent, so a DENY on it is Indeterminate (`ATR-9`). Confirm it: the startup line `ip2region databases: [IPv4]. The IPv6 addresses are unavailable: ...` (the provider's `versions`), and the audit events' client addresses are all IPv6. | Load an IPv6 database with [`cosec.ip2region.ipv6-location`](./configuration.md#feature-switches), or set `onMissing: nomatch` on a statement where letting an unlocatable client past it is what the policy means ([Request attributes](../integration/web.md#request-attributes)). |
| `INDETERMINATE: MatcherFailed` / `matcher` | A DENY statement's condition, or the condition of its policy, could not be evaluated. That includes a DENY on `http.header.<name>` when the request sent that header more than once with different values (`Header [<name>] arrived with different values; a condition on it cannot decide.`); the same header repeated with one value reads as that value. A failing ALLOW never makes a request `503`: an ALLOW on such a header does not match. | Fix the condition, or the client that sends the header twice. |
| `INDETERMINATE: BoundaryUnavailable` / `boundary` | A statement of the tenant's boundary could not be evaluated for a permitted request, for example because the provider of an external attribute it reads failed or timed out. | Fix the boundary or the provider. |
| `INDETERMINATE: RateLimiterUnavailable` / `rate_limiter`, for new clients of one rule, without Redis | The per-JVM `LocalRateLimiter` holds its maximum of buckets for that rule, or in all: `The local rate limiter holds its maximum of buckets (rule [<id>]: <n> of <max>, all: <n> of <max>).` in the stack trace of `Rate limiter unavailable: rule [<id>] refuses the request.` Buckets it already holds keep counting; a new key cannot be counted, so a rule with `strictFailure: true` refuses it (a lenient rule is skipped, at `WARN`). Typically the rule's `key` reads something a client can vary. | Key the rule on the principal or a trusted address, or count in Redis ([The local limiter is bounded](../policies/rate-limiting.md#the-local-limiter-is-bounded)). |
| `INDETERMINATE: RateLimiterUnavailable` / `rate_limiter` | A rule with `strictFailure: true` could not be counted (`Rate limiter unavailable: rule [<id>] refuses the request.` at `ERROR`). With `CROSSSLOT` in that line, on every permitted request of a Redis Cluster, see [`too-many-requests`](#too-many-requests). | Restore Redis, or drop `strictFailure`. |
| `INDETERMINATE: RequiredListenerFailed` / `required_listener` | A `REQUIRED` decision listener failed or timed out on a permit (`REQUIRED decision listener [<name>] failed on request [<id>].`), or a permit came through a break-glass binding with no `REQUIRED` listener (`Request [<id>] permitted through break-glass, but no REQUIRED listener is registered.`). | Fix the listener's store, or register a `REQUIRED` listener before using break-glass ([Decision listeners](./observability.md#decision-listeners)). |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator` | The authenticator failed or took longer than the authentication timeout (20 % of `cosec.pipeline.timeout`): for example the Redis API key store is unreachable. | Restore the store. |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`, for every request of one authenticator | An `Authenticator` of your own names its credential's method `jwt`, which is reserved for CoSec's token (`The authentication method [jwt] is CoSec's own token's ...`). | Name the method after your credential, such as `Identity.Method("ldap")` ([Extending](../architecture/extending.md#authenticators)). |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`, for the requests of some principals or keys | The authenticator gave a principal whose id, tenant, credential id or claimed roles hold an unpaired UTF-16 surrogate (`Principal [<id>] of tenant [<tenant>] holds an id with an unpaired UTF-16 surrogate ...`), or an API key's stored record names one (`API key [<id>] holds an id with an unpaired UTF-16 surrogate.`, `IDN-10`). Such an id would share a Redis key with another, so the request fails closed instead. | Fix the authenticator, or the key's record: an id is Unicode text. |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`, for every request with one key | The key is stored in another format, which is refused when read (`API key format [null] is not 3.`; `null` means the record has no `format`, as a key written by 7.x; `2` means an 8.0 pre-release wrote it). | A 7.x key: convert the keys ([API keys narrow, and memberships decide](./upgrading.md#api-keys-narrow-and-memberships-decide)). A pre-release key: issue a new key and delete the old one; `cosec-migrate api-keys` refuses to count it converted. |
| `DEADLINE_EXCEEDED` / `deadline` | The whole evaluation took longer than [`cosec.pipeline.timeout`](./configuration.md#pipeline). | Find the slow stage in the trace. |

A DENY still decides: if a DENY matches, the request is `401` or `403` even when a document is unavailable.

### `internal-error` {#internal-error}

An exception CoSec does not model: a defect, in CoSec or in an extension of yours. The log has
`Request [<requestId>] failed with an unmodelled exception.` at `ERROR`, with the stack trace, which names the class
that threw. The response body never carries the message.

On a gateway, a permit whose identity cannot be forwarded is a `500` too, logged as
`Request [<requestId>]: the identity cannot be forwarded.` with the cause:

- `The credential of [<subject>] never expires: a service could not bound the identity.`: an API key without
  `expires-at` (or a token without `exp`). Give the key an expiry, or set the route's metadata `cosec-identity: false`.

A route whose metadata `cosec-app-id` cannot be an app id (empty, `*`, with `:`, `/` or a control character, or longer than
128 characters) answers `500` on every request, logged as `The cosec-app-id of route [<routeId>] cannot be an app id:
...` with the unmodelled-exception line above: fix the metadata ([Gateway](../integration/web.md#gateway)). One that
is not a string (a number, a boolean, a list or a map) does the same, logged as `The cosec-app-id of route [<routeId>]
is a <type>, not a string: ...`: quote the value, and give one app.
- `The identity of [<subject>] is <n> bytes encoded, over the limit of <max>: ...`, `Claim [<name>] is <n> characters,
  over 1024.` or `Claim [<name>] is a <type>, not a string, number or boolean.`: fewer roles or
  [`cosec.gateway.identity.claims`](./configuration.md#gateway-identity), or a larger `max-size` on the gateway and
  the services.

## A permitted request answers 500 with the gateway's own error body {#a-permitted-request-answers-500-with-the-gateways-own-error-body}

CoSec decided to permit, and the gateway then failed to reach the route's target. The answer is a `500` whose body has no
`type`: Spring Boot's default error object (`timestamp`, `path`, `status`, `error`, `requestId`), not a problem document.
The gateway log has, at `ERROR` from `AbstractErrorWebExceptionHandler`, `500 Server Error for HTTP GET "<path>"` followed
by `java.net.UnknownHostException: Failed to resolve '<host>'`: the host of the route's `uri` does not resolve, for
example a Kubernetes Service that does not exist in the gateway's namespace (the demo route `cosec-auth` of `k8s/`
points at one it does not create).

CoSec's own refusals tell apart: they are always `application/problem+json`, and their `type` begins with
`https://cosec.ahoo.me/problems/` ([Web integration](../integration/web.md)). A `5xx` without that type is the gateway's
or the target's, and no decision of CoSec's caused it: do not look in the documents. Fix the route's `uri`, or create
the Service or the DNS name it names.

## Request allowed unexpectedly

1. **Read the deciding statement** in the audit event or the explanation: `source.policy`, `binding` and
   `breakGlass`.
2. **A broad allow.** Look for an allow with `*` or `**` actions or resources, bound to `*` subjects, or without a
   condition. Add a deny, or narrow the allow.
3. **A deny that does not match.** Its action, its resource and its condition must all match. Explain the request to
   see where the deny missed.
4. **Break-glass.** `breakGlass: true` and `source.policy` `cosec:root` mean a break-glass binding of `cosec:root` permitted
   the request; such bindings expire on their own and can be revoked through the control plane.
5. **An old revision.** `snapshotVersion` names the revisions the decision read. If they are older than what was
   published, see [Decisions use an old revision](#decisions-use-an-old-revision).
6. **A mapped route.** A platform DENY or rate limit rule on `http:*` stops applying to a path once a route map maps
   it. A host with a route map logs `[<doc>] revision <n>: <count> mapped route(s) escape its http: statements …` at
   `WARN` when it installs such a document; write each statement it names for the mapped action too
   ([What CoSec warns about](../scenarios/route-maps.md#what-cosec-warns-about)).

## A check in code answers false though the documents permit it {#a-check-answers-false}

`AuthorizationChecker.check` is `false`, and `decide` answers `Deny(null)` (no statement), where the request itself
would be permitted. A check is refused before any document is read when:

- **Its request has ended** (`PIP-26`): the context was kept after the response was written, in a field, a cache, a
  coroutine or message that outlived the request. Check within the request; work that runs afterwards carries its own
  authorization ([Checks in code](../integration/checks.md#how-a-check-relates-to-the-request-pipeline)). A check
  during a streamed body or an async servlet request is still within it; one in a Spring MVC error page (the ERROR
  dispatch) is not.
- **The pipeline did not make the context**: one built by hand, or a mock. The adapters' readers do not even return
  such a context (`PIP-27`): `exchange.securityContextOrNull()` and `request.securityContextOrNull()` are `null`, a required
  `SecurityContext` handler parameter fails, and the log says `A SecurityContext is bound … that CoSec's pipeline did
  not make, and it is ignored` at `WARN`, at most once a minute for each reader. In a test, take the context of a request the pipeline permitted
  (`PipelineFixture.securityContext`, `PermittedRequest`).
- **The action has no `:`**, or names what enforcement refuses (an `http` verb with a lower-case letter, a path a
  request would get `400` for: `PIP-15`).

Three of these are mistakes in the calling code, and the log names them. The logger is
`me.ahoo.cosec.runtime.AuthorizationChecker`. Each kind is logged at `WARN` at most once a minute, so a loop of checks
does not flood the log, and every occurrence at `DEBUG`. The message names the request id, the action and the
resource:

| Message starts with | What to change |
|---|---|
| `Check denied before any policy was read: the SecurityContext is not one the pipeline made` | Pass the context the adapter bound, or in a test one from `PipelineFixture` or `PermittedRequest`. |
| `Check denied before any policy was read: its request has ended` | Check within the request; a transport of your own closes its `Outcome` only when the response is complete. |
| `Check denied before any policy was read: the action is not` | Write the action as `service:verb`, or `http:` with the method in upper case. |

A path that enforcement refuses is not logged: it can come from a client.

## Decisions use an old revision

The audit event's `snapshotVersion`, or the span's `cosec.snapshot.version`, is older than the revision the control
plane published.

- **The instance cannot reach Redis.** It keeps deciding with what it has installed, and logs
  `Snapshot document [<doc>] revision <n> has not been confirmed for <lag>.` at `WARN` after 6 revalidation intervals (60 seconds),
  `... is stale: not confirmed for <lag>.` at `ERROR` after 30 (5 minutes). Restore Redis.
- **The instance missed an invalidation message.** It catches up within
  [`revalidate-interval`](./configuration.md#snapshot) (10 seconds by default, at most 5 minutes). The pass cannot be
  turned off: a longer gap with Redis healthy means the instance's revalidation pass is failing; its log says why.
- **The instance reads another namespace.** Every instance and the control plane must share
  [`cosec.key-prefix`](./configuration.md#key-prefix) and the Redis.
- **The newer revision does not compile in time.** The instance read it, but its compilation outlasted the reload's
  30 seconds: it logs `Compiling snapshot document [<doc>] revision <n> did not end within <budget>; ...` at `ERROR` and
  keeps deciding on the revision it holds ([A compilation does not end](#a-compilation-does-not-end)).
- **Redis lost the write.** After a failover the instance may log
  `Snapshot document [<doc>] regressed from revision <a> to <b>; ...` at `ERROR`: publish the change again.
  [`cosec-migrate verify-chain`](./upgrading.md#verify-the-revision-chains) names every document whose revision
  chain lost a record.

The control plane's propagation view shows how many instances have a revision installed
([Snapshot health](./observability.md#snapshot-health)). On an instance, `cosec.snapshot.lag` and
`cosec.snapshot.lagging` show how far behind it is, and `cosec.snapshot.regressions` counts lost writes
([Snapshot metrics](./observability.md#snapshot-metrics)).

## `propagation` answers `unknown` above 0

`GET /documents/{key}/revisions/{rev}/propagation` counts in `unknown` the live instances (a status in the last minute)
whose resident record, the documents an instance holds, cannot be read (`CTL-75`). Until `unknown` is 0, the answer
cannot say that no instance is behind, even when `installed` equals `holding`: an unknown instance may hold the
document at an older revision. The causes, in the order to check them:

- **Instances still on 8.0.0-beta.5** write no resident record. Expected while the data plane is upgraded after the
  control plane ([Upgrading between previews](./upgrading-previews.md#from-8-0-0-beta-5)); `unknown` falls as they are
  replaced. No log line names them: a missing record is not an error.
- **An instance whose resident write keeps failing** logs `Writing the resident documents failed; the next cycle writes
  it again.` at `WARN` every report interval, with the cause (a Redis user without `~<s>:shadow:resident:<instance>*`
  is the usual one: [Storage](./storage.md#redis-keys)). Its status still writes, so it stays live and unknown until a
  write succeeds; the write is retried every cycle.
- **A resident record that does not decode** makes the control host log `The resident documents of instance
  [<instance>] do not read: propagation counts it unknown (<why>).` at `WARN`, at most once every 5 seconds. A later
  format in `<why>` is an instance on a newer version than the control host (upgrade the control plane); anything else
  is a corrupt value: restart or stop that instance, whose next write replaces it.

## A service behind the gateway refuses requests

A service in [`cosec.pipeline.mode=gateway`](./configuration.md#pipeline) reads the caller from the `CoSec-Identity`
header the gateway forwards. A request without the header proceeds as anonymous; a header it cannot accept is never
read as anonymous:

| Status | Cause | Fix |
|---|---|---|
| `400` `invalid-request` | The header is not base64url JSON of format v1, has an unknown or missing member, names an id with an unpaired UTF-16 surrogate (`IDN-10`) or a tenant that cannot be a tenant id, such as one with `/` (`SCP-13`), is larger than [`cosec.pipeline.identity.max-size`](./configuration.md#pipeline), arrived more than once, or names a scope that does not resolve on this service. | Something other than the CoSec gateway sets the header, or the gateway's `cosec.gateway.identity.max-size` is larger than the service's limit. |
| `401` `token-invalid` | A version this service does not read, no `auth.exp`, or an identity issued later than now plus [`cosec.authentication.revocation.skew`](./configuration.md#authentication). | Upgrade the service with the gateway; synchronize the clocks. |
| `401` `token-expired` | The credential expired, or the identity was issued longer ago than [`cosec.pipeline.identity.max-age`](./configuration.md#pipeline) plus the skew. | Synchronize the clocks; a request that waits long between the gateway and the service needs a larger `max-age`. In a test without a gateway, a header made once and reused: make one per request ([`IdentityHeader`](../integration/web.md#services-behind-the-gateway)). |

The response says only the problem, never why, in every environment. The reason is in the service's log, from the
logger `me.ahoo.cosec.runtime.identity.IdentityGate`:

```text
Request [<id>]: the forwarded identity is refused (<status> <problem>), <reason>.
```

at `WARN`, at most once a minute for each problem type so a flood of bad headers does not flood the log, and every one
at `DEBUG`. `<reason>` is the field that failed, such as `The identity header is malformed:
$.rid is empty.`, or the time that is out of bounds. Text of the header in it, such as a claim or member name, is
[escaped as ids are](#a-log-line-names-an-id-with-u-escapes) and cut to 64 characters, and at most three names are
listed; a header that is not JSON is named by the line and column where it fails, never by what it holds. A service in gateway mode records no decision events and no
audit, but it counts every refusal, also those the log does not print, in
[`cosec.identity.refused{problem}`](./observability.md#metrics): a rise names the problem, and the log says why.

Only the gateway may be able to reach such a service: it believes any header it receives. Startup logs this at `WARN`.

**Every request is anonymous.** The gateway forwards the identity only to a route that receives it: a route to a
registered service (`uri: lb://...`), or one whose metadata says `cosec-identity: true`. A route to `http://` or
`https://` without that metadata, or with `cosec-identity: false`, sends no identity, so the service behind sees
anonymous requests. Mark the route `cosec-identity: true` when it leads to your own service
([Forwarding the identity](../integration/web.md#forwarding-the-identity)). A service that verifies the token itself
and stopped receiving it needs `cosec.gateway.identity.forward-authorization=true` and such a route.

## A refusal is answered with CoSec's body though a renderer is declared {#a-refusal-is-answered-with-cosecs-body-though-a-renderer-is-declared}

```text
Request [<id>]: the ProblemRenderer [<class>] failed with <exception>, so the <status> <slug> refusal is answered with CoSec's own body (PIP-34). ...
```

The application's `ProblemRenderer` threw, or answered no bytes, for that refusal, and CoSec answered it with its own
body ([Problem renderer](../architecture/extending.md#problem-renderer)). The request was still refused, with CoSec's
status and headers. The line comes at `WARN` at most once a minute, with the exception's stack, and at `DEBUG` every
time; `cosec.problem.render.failed` counts every one. Fix the renderer: it must answer bytes for every `ProblemType`,
the ones a later release adds included, so give it a default branch. A body that is never the renderer's, and no such
line, means the renderer is not wired: it must be a bean, and there must be one.

## Instance is not ready

Readiness refuses traffic until the platform document and every document in
[`prewarm-apps` and `prewarm-tenants`](./configuration.md#snapshot) are installed; liveness is not affected. An
instance that never becomes ready:

- **No platform document is published.** Publish one with `cosec-migrate publish`. The instance logs it, at startup and
  then every 6 revalidation intervals while it lasts: `No platform document under [cosec:snapshot:platform] in Redis <host:port/db>:
  this instance is not ready ...`. The line names the key, the key prefix and the Redis it read, never a credential: a
  first deployment that has published nothing, a wrong `cosec.key-prefix` (the publisher's must be the same) or a wrong
  Redis database are the usual causes. One INFO line says when the document is installed.
- **A prewarmed document cannot be loaded or compiled.** The logs name it (`Loading snapshot document [<doc>]
  failed.`). Fix or publish the document, or remove it from the prewarm list.
- **Redis could not be read.** The platform document and the prewarmed ones are read again every 1 to 30 seconds
  (`Snapshot prewarm failed for [...]; retrying in ...` at `WARN`), without a request: the instance becomes ready once
  Redis answers (`SNP-21`). If it does not, the cause is one of the two above, or Redis still refuses the instance.

The `cosecSnapshot` health indicator shows the state: `platform` (`installed`, `invalid`, `failed`, `absent` or
`loading`; `invalid` is a stored document that does not compile) and `prewarmed`. Add it to the readiness group to see it in the probe:
`management.endpoint.health.group.readiness.include=readinessState,cosecSnapshot`.

### `cosecSnapshotEviction` is DOWN {#cosecsnapshoteviction-is-down}

```text
Redis may evict CoSec's policy snapshot: maxmemory-policy is {<node>=<policy>}, not noeviction: an instance that loads an app or tenant document Redis evicted reads it as absent, and its DENY statements silently stop applying. Set maxmemory-policy to noeviction.
```

Each instance reads every Redis node's `maxmemory-policy` every [`revalidate-interval`](./configuration.md#snapshot),
on a thread of its own, and logs this at `ERROR` from
`me.ahoo.cosec.spring.boot.pipeline.EvictionPolicyMonitor` when a node starts to allow eviction: someone ran
`CONFIG SET maxmemory-policy`, Redis failed over to a node set up otherwise, or a node restarted with another
configuration. The `cosecSnapshotEviction` health indicator is then `DOWN`, with the same text in its `eviction`
detail. Requests are still decided; an app or tenant document Redis evicts is read as absent by the next instance that
loads it. Set `noeviction` on every node again; the indicator is `UP` after the next reading, and the instance logs
`Redis no longer evicts CoSec's policy snapshot.` at `INFO`.

`Cannot tell whether Redis may evict ...` in the detail means the policy could not be read (`INFO` denied, failing or
timing out, or no node to ask) under `eviction-check=fail`: allow `INFO` (`+info`), or, when you have set
`noeviction` yourself, set [`eviction-check=warn`](./configuration.md#snapshot), under which an unreadable policy is
`UP` with `eviction: unknown` and logged once at `WARN` ([Redis requirements](./storage.md#redis-requirements)).

The indicator is an alert: keep it out of the readiness group, which `cosecSnapshot` alone serves. In it, one change to
Redis would take every instance out of rotation at once.

**The control-plane host** has one more reason: its readiness group holds `cosecControlStoreFormat`, so a control store
in the format before 8.0.0-beta.5 leaves it unready, with a `format` detail naming `cosec-migrate repair-references` and
one `ERROR` at startup. Stop the host, run the command, start it
([The control-plane host](./deployment.md#control-plane-store)). Its probes are on the management port 8081: on 8080 the
pipeline refuses a kubelet.

## A pod runs out of memory {#a-pod-runs-out-of-memory}

Read which memory ran out; the knob differs:

| What you see | Cause | What to change |
|---|---|---|
| `java.lang.OutOfMemoryError: Java heap space` in the log, a heap dump in `data/` (the image's own options write it), and the pod restarts | The compiled documents and the gateway's own allocation do not fit the heap | The documents keep within a quarter of the heap (`SNP-24`): check that the heap the startup line names (`heap <n> MiB (Runtime.maxMemory())`) is the one you set, and raise the heap together with the container's limit ([How many documents fit](./deployment.md#memory)) |
| `java.lang.OutOfMemoryError: Direct buffer memory` | Netty's buffers reached `-XX:MaxDirectMemorySize` under load | Raise the direct memory with the limit; read `jvm_buffer_memory_used_bytes{id="direct"}` |
| The pod is `OOMKilled` (exit code `137`) with no Java error | The container's limit is below the heap plus the direct memory plus about 250 MiB for the JVM's native part | Raise the limit, or lower `-Xmx` and `MaxDirectMemorySize`: the limit is the sum of the three, plus a quarter ([Memory](./deployment.md#memory)) |

## Probes answer 401 or 503, or pods restart before the first publish {#probes-answer-401-or-503}

A readiness or liveness probe answers CoSec's `application/problem+json`: `503` `unavailable` before the first platform
document is installed, `401` `unauthorized` after it; or pods restart in a loop on a new deployment, before the first
document was published.

To CoSec a probe is a request like any other (`STA-4`, [Actuator on an application](../integration/web.md#actuator-on-an-application)):
on an application's application port, and on a WebFlux application's management port, it passes the pipeline, which
answers `503` while no platform document is installed and `401` to an anonymous caller after it unless a statement
permits the path. Liveness is not touched in Spring's state, but an HTTP liveness probe is a request, so it fails with the
rest, and the kubelet restarts the pod.

- **Readiness over HTTP:** permit `GET` on the two probe paths for anyone, with one statement
  ([the policy](../integration/web.md#actuator-on-an-application)). Before the first publish it still answers `503`, the
  right answer for "not ready". Spring Boot 4 serves the probes on every platform (`management.endpoint.health.probes.enabled`
  is `true` by default).
- **The management port is not only the actuator.** On WebFlux Spring Boot's management server also serves the
  application's own functional routes, behind CoSec; CoSec's control API and login routes answer on the application port
  (and the control API on its service port) and are a `404` on the management port (`CTL-69`). A control call that
  answers `404` only on the port a probe or a scrape uses is on the wrong port.
- **Liveness and startup:** on WebMvc, point the probe at `management.server.port`, which is outside CoSec. On WebFlux the
  management port is under CoSec as well: use a `tcpSocket` probe on the application port. Never an HTTP liveness probe on
  the application port of an application.

## A condition does not compile

- `` `<path>` is a credential header, which no attribute reference may read ``: a condition or a rate limit `key`
  reads `http.header.authorization`, `proxy-authorization`, `cookie`, `set-cookie`, `x-api-key`, `cosec-identity`, or
  the header [`cosec.pipeline.api-key-header`](./configuration.md#pipeline) names.
  No policy may read a credential. Condition or key on a principal attribute or the scope instead
  ([Policy language](../policies/language.md#headers)).
- `` `<path>` is not a header name: a header name is an ASCII token (RFC 9110) ``: the name after `http.header.` has
  a character outside ASCII letters, digits and ``!#$%&'*+-.^_`|~``, such as a space or a look-alike letter.
- A condition on an `ext.*` attribute the platform document does not declare and no attribute provider of the host
  serves. A change is compiled against the attribute providers of the control host.
- `` is not a day of the week: `<value>` ``: `daysOfWeek` takes exactly `MON`, `TUE`, `WED`, `THU`, `FRI`, `SAT` and
  `SUN`, not `MONDAY`, `mon` or ` Fri ` ([Policy language](../policies/language.md)). On the data plane a stored
  document that spells a day another way is installed as failed, and the requests that read it answer `503`.
- A path pattern over a cost bound (`DOC-4`, [Policy language](../policies/language.md#path-pattern-bounds)):
  `the pattern is <n> characters, more than 2048`; `segment [<s>] has <n> parts (*, ?, {name} and the text between
  them), more than 16`, where consecutive `*` count once; or, at `$`, `The document's path pattern segments that mix
  *, ? or {name} with other text hold <n> tokens and literal characters in all, more than 2048`. Split a long glob
  into whole segments (`*` or `{name}` alone in a segment costs nothing), keep the text of a glob segment short, or
  fold several statements' patterns into fewer; a segment after a leading `**` counts twice. On the data
  plane a stored document over a bound is installed as failed, and the requests that read it answer `503`.

## A compilation does not end {#a-compilation-does-not-end}

`Compiling snapshot document [<doc>] revision <n> did not end within <budget>; the load fails, and the revision is not
compiled again while that compilation runs.` at `ERROR`: the compiler took longer than the load's budget
(the cold-load timeout, 2 seconds, for a document not yet held; 30 seconds for a reload) on that stored revision. The
requests that need it answer `503` while it is not held, or are decided on the revision held before. The compilation
keeps one compile thread until it ends; two such revisions at once hold both default threads, and then no other
document compiles until one ends: every load fails at its deadline, closed. A document the control plane validated compiles in well under a millisecond: look for a
revision written to Redis outside the control plane, and publish a corrected revision, which compiles as any other
([Storage](./storage.md#how-instances-read-documents)).

For a reload, this differs from a reload that cannot read Redis, which logs only `Reloading snapshot document [<doc>]
failed; keeping revision <n>.`: here the instance read the newer revision and does not decide on it. Until its
compilation ends, every revalidation pass meets that revision again, and the line repeats at `ERROR` each time; the
instance keeps the revision it holds (`SNP-22`), which is no longer confirmed, so the lag lines and
`cosec.snapshot.lagging` follow after 6 and 30 intervals as for an unreachable Redis. A tenant in
[`fail-closed.tenants`](./configuration.md#snapshot) is refused (`503`) once that lag passes `fail-closed.max-lag`; every
other tenant is decided on the old revision until the compilation ends or a corrected revision is published. Where to
see it besides the log ([Snapshot health](./observability.md#snapshot-health)): `cosecSnapshot`'s `compileTimedOut` and
`compileTimedOutFirst` name the document and the revision (`["tenant:acme@8"]`) while the compilation runs, and
`cosec.snapshot.compile.duration{result="failure"}` counts each load that ended this way, while
`cosec.snapshot.installed{state="invalid"}` does not move.

## Shadow window waiting for an instance

The control plane logs `Shadow window <w> of change <c> is waiting for [<instances>]` and the window does not close,
whatever the other instances report: those instances reported on the window and have no report now (stopped, scaled
down, or their report lost in a Redis failover). Their evidence is missing, so the window waits for them (`CTL-56`).
An instance that still serves writes its report again within its report interval (10 seconds at the default). One that is
gone for good leaves the window open until `cosec.control.shadow.max-duration`, when it closes *insufficient* with the
instance named in its summary's `vanished`: publish it with the summary acknowledged, or abandon it and stage it again
to start a window with the instances serving now ([Testing changes](../policies/testing.md#trying-changes-in-shadow-mode)).

## Shadow window waiting for an acknowledgement

The control plane logs `Shadow window <w> of change <c> is waiting for the acknowledgement of [<instances>]`, and
`GET /changes/{id}` names them in `shadow.waitingFor`: the window has its samples, or not yet, but cannot pass while a
live instance (one with a status in the last minute) does not list it in its status (`CTL-10`). An instance cut off
from Redis for longer than that writes no status: it is neither waited for nor acknowledging, and installs the
published revision having compared nothing. A host with shadowing off writes no status at all.

- **An instance of an older version** sends no acknowledgements. Finish the rolling upgrade: once the old instances are
  gone, their statuses expire within two minutes and the window no longer waits for them.
- **An instance that cannot read the staged list** logs `The staged list cannot be read since it was at <instant>` and
  its `cosec.shadow.staged.age` rises. Fix its Redis access (the data-plane user reads `<s>:candidate-staged:all`,
  [Storage](./storage.md#redis-keys)).
- **An instance that started during the window** acknowledges it within a few report intervals, once it has compiled
  the candidate.
- **An instance whose status does not decode** counts as live and acknowledging nothing (`CTL-73`): the control host
  logs `The status of instance [<instance>] does not read: it counts as live and acknowledging no shadow window until
  it reads or expires, at most two minutes after its last write (<why>).` at `WARN`, at most once a minute per
  instance, and `cosec.control.shadow.status.unreadable` counts each read of it. An instance that stops writing
  stops holding the windows two minutes later, when its status expires. A later format in `<why>` is a newer version during a rolling
  upgrade: finish the upgrade of the control hosts, or roll that instance back. Anything else is a corrupt value or a
  crash-looping instance: stop or fix the instance by its id; its status expires two minutes after its last write.

If **no** instance acknowledges any window (each one's `waitingFor` names every live instance), the instances hold no
staged list: they log `The staged list cannot be read`, or `<s>:candidate-staged:all` has no `.kept` marker (a store
the control plane staged nothing on since it was emptied; the next stage, or the shadow pass, writes it). Fix that and
they acknowledge within a few report intervals.

Only instances that take part in shadowing are waited for. A host with `cosec.authorization.shadow.enabled=false` (the
control-plane image sets it) writes no status at all, and one whose `CandidateSource` is your own writes a status that
says it does not shadow: neither holds a window, and `propagation` still counts the latter.

If `waitingFor` is empty and the window still does not close, it waits for samples: its documents get too little
traffic, and it closes *insufficient* at `cosec.control.shadow.max-duration`
([Testing changes](../policies/testing.md#trying-changes-in-shadow-mode)).

## Shadow window blocked by a candidate error

A shadow window is blocked as soon as one instance reports `CANDIDATE_ERROR`: it cannot compile the candidate, so it
cannot compare decisions, and the change is never published. The usual cause is a candidate that reads an `ext.*`
attribute whose provider runs on the control host but not on that instance. Attribute providers, and the types they
declare, must be the same on every data-plane instance and on the control host. Deploy the provider everywhere before such a
change is shadowed ([Testing changes](../policies/testing.md)).

## The typed control client does not read a document {#the-typed-control-client-does-not-read-a-document}

`ControlClient` throws `SnapshotFormatException` with `The control plane's answer does not read: a <entry> has members
this version does not know`: the control-plane host is a later version than the `cosec-control-sdk` the application
runs, and the document holds a member only the later one knows. The typed client refuses it rather than drop it, since
an `:apply` of what it read would remove that member from the document. Upgrade `cosec-control-sdk` to the host's
version; until then, read that document through `ControlService`, as JSON ([Changing policies from your
code](../integration/checks.md#changing-policies-from-your-code)). A value of a closed set the client does not know
(a change state, a conflict) is not this error: it reads as unknown, with its text.

## Social sign-in is refused: too many sign-ins in progress {#social-sign-in-is-refused-too-many-sign-ins-in-progress}

`authorize` throws `SocialAuthenticationException`
`The [<provider>] provider has too many sign-ins in progress: try again shortly.`, and an instance logs at most once a minute `Refused <n> [<provider>] sign-in start(s) since the last
such line: <n> social sign-ins are in progress ...` at `WARN`. The deployment holds as many sign-ins in progress as it
may: a 64th of the memory Redis reports, at 512 bytes a sign-in, or 100,000 when Redis reports none (the startup line
`Social sign-in states: ...` says which; [Storage](./storage.md#redis-keys)). Starting a sign-in is anonymous, so a
steady stream of these lines is something starting sign-ins in a flood: rate limit the start route at your edge. A
start refused writes nothing, and a sign-in already in progress is not affected. With the in-memory store
(`InMemorySocialStateStore`, tests and a single instance) the bound is 10,000.

## Startup failures

### Startup fails on an unknown property

```text
CoSec does not read <n> cosec.* properties, which would do nothing:
  cosec.authentication.enabled
Remove or rename them: https://cosec.ahoo.me/operations/upgrading#removed-and-renamed-properties
```

A `cosec.*` key that no CoSec module reads: a typo, or a key a release renamed or removed
([the table](./upgrading.md#removed-and-renamed-properties)). Rename or remove it; no property
[turns the check off](./configuration.md#unknown-properties).

On Kubernetes, with 8.0.0-alpha.1, keys such as `cosec.gateway.service.host` and `cosec.gateway.port` came from service
links, and every pod of the namespace crash-looped on `CoSec does not read 8 cosec.* properties`. For every Service
whose name is `cosec` or starts with `cosec-` (the manifests in `k8s/` name theirs `cosec-gateway`), Kubernetes sets
variables such as `COSEC_GATEWAY_SERVICE_HOST` and `COSEC_GATEWAY_PORT` in the pods it starts afterwards, and Spring
reads them as `cosec.*` keys. Since 8.0.0-alpha.2 the check skips exactly those variables, only in the process
environment ([Unknown properties](./configuration.md#unknown-properties)): a mistyped variable such as
`COSEC_JWT_SECRT`, and the same key in `application.yaml` or a system property, still fail. On alpha.1, or to keep the
variables out of the pod altogether, set `enableServiceLinks: false` in the pod spec, as the manifests in `k8s/` do
([What the manifests set](./deployment.md#what-the-manifests-set)); services are then found by DNS, which CoSec uses
anyway.

### Startup fails on the pipeline mode

- `cosec.pipeline.mode=gateway on a Spring Cloud Gateway host: ...`: a gateway always decides with policies. Remove
  the property from the gateway; set it on the services behind it.
- A binding failure for `cosec.pipeline.mode`: the value is neither `enforce` nor `gateway`.

### Startup fails because the network boundary is not stated {#startup-fails-because-the-network-boundary-is-not-stated}

```text
cosec.pipeline.mode=gateway: this service believes any CoSec-Identity header it receives, and the header carries no signature, so whoever can reach this service is whoever they claim to be ... State it once it is true: cosec.pipeline.identity.only-gateway-can-reach=true (the environment variable COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH=true). It is not set (STA-7, ...).
```

A service in `cosec.pipeline.mode=gateway` trusts the network (`STA-7`). Make it true, then write the line: a
`NetworkPolicy` that admits the gateway's pods only
([`k8s/examples/gateway-mode-service-networkpolicy.yaml`](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/examples/gateway-mode-service-networkpolicy.yaml)),
mutual TLS, or a private network only the gateway is on. `It is [false]` (or `1`, `on`, an empty value) means the
property is written with a value that is not `true`. Do not write it to get past the message before the network
holds. After it, startup logs one `INFO` line.

The same message opens with `cosec.pipeline.caller=forwarded-identity:` on a host that decides with policies and takes its
caller from the identity, such as the [control-plane host](./deployment.md#the-control-plane-host) or an IAM service that
embeds the control plane (`STA-9`), and with `the web gate of this host is an IdentityGate bean of the application` when the
application declared its own gate. It names two lines: `only-gateway-can-reach=true` when only the gateway reaches the
host, `cosec.pipeline.identity.only-trusted-callers-can-reach=true` (`COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH`)
when services call it directly, as they do the service port of the `cosec-control` image, which is on by default; beside a
service port the gateway's line is refused, because it would be false. The network first, one policy per port
([`k8s/control/`](https://github.com/Ahoo-Wang/CoSec/tree/main/k8s/control)), then the line, which on Kubernetes the
overlay writes, beside the [boundary check](#the-control-hosts-boundary-check-fails).

### The control host's boundary check fails {#the-control-hosts-boundary-check-fails}

```text
FAIL reached the control host at 10.244.0.7:8080 from a pod no NetworkPolicy admits (curl exit 0): the cluster does not enforce the policies, and COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true is false. Whoever reaches the host can state any principal.
```

The Job `cosec-control-boundary-check` of [`k8s/control/`](https://github.com/Ahoo-Wang/CoSec/tree/main/k8s/control)
connects to every port of every control-host pod from a pod no policy admits ([Deploying](./deployment.md#kubernetes)). A
connection that is answered means the cluster's network plugin does not enforce the three NetworkPolicies, so the overlay's
statement of the boundary is false, and any workload of the cluster can state a platform administrator to the host. Take
the statement out of your overlay (the host stops) until the plugin enforces the policies, then delete the Job and apply
again (always delete it before an apply: a Job's pod template cannot change, and one that finished does not run again).
Never delete the Job to make the failure go away; `k8s/overlays/dev` is for a cluster nothing else runs on.

`FAIL could not tell` means the host refused every connection, or had no address, for ten minutes: it never listened
(see why with `kubectl logs deploy/cosec-control`), or the plugin refuses a connection instead of dropping it. In the
second case, check by hand that a pod of another label cannot connect.

### Startup fails on the control-plane image's settings {#startup-fails-on-the-control-plane-images-settings}

```text
cosec-control-server verifies no credential and issues no token: it believes the CoSec-Identity header it is sent, under the statement that nothing but the gateway and the services you name can reach it. cosec.jwt.enabled=true would wire verification or a login that nothing here uses ... (STA-10)
```

The `cosec-control` image refuses `cosec.jwt.enabled`, `cosec.api-key.enabled` and `cosec.authentication.login.enabled` set
to `true`, and `cosec.control.enabled=false` (`cosec-control-server runs the control plane`): it verifies no credential and
issues no token, and without the control plane it would be up and serve nothing. Remove the key. A control plane that
verifies tokens or signs people in is an application of your own, with `cosec-control`; the gateway image is the
read-only one.

### Startup fails on two control services {#startup-fails-on-two-control-services}

```text
This instance runs the control plane, and cosec.control.service-url names a control-plane host as well: an application has one ControlService. ... (STA-11)
```

The instance has `cosec-control` (and `cosec-cocache`) with the control plane on, and `cosec.control.service-url` set too.
An application has one `ControlService`: the in-process one where it runs the control plane, the HTTP one where it calls a
host. To embed the control plane, remove `cosec.control.service-url`. To call the host, set `cosec.control.enabled=false`
(or leave `cosec-control` out). `cosec.control.service-url needs cosec-control-http` means the host is named and the
module that calls it is missing: add `me.ahoo.cosec:cosec-control-http`.

### Startup fails on two problem renderers {#startup-fails-on-two-problem-renderers}

```text
PIP-34: 2 ProblemRenderer beans of the application, [first, second]: a refusal has one body, so CoSec takes one renderer. Declare one.
```

A refusal has one body, so the starter takes one `ProblemRenderer` bean and does not choose between two. Keep one, or
declare one that delegates to the others ([Problem renderer](../architecture/extending.md#problem-renderer)).

### Startup fails on a hidden-method filter behind a gateway {#startup-fails-on-a-hidden-method-filter-behind-a-gateway}

```text
cosec.pipeline.mode=gateway: spring.mvc.hiddenmethod.filter.enabled=true. The gateway decided the method of the request line, but this service would run a POST as the method a form field `_method` names ... (STA-6).
```

Remove `spring.mvc.hiddenmethod.filter.enabled`, `spring.webflux.hiddenmethod.filter.enabled` and any
`HiddenHttpMethodFilter` bean of the application (`a HiddenHttpMethodFilter bean, [name]`): the gateway cannot remove
a `_method` field from a form body, so a `DENY` on `http:DELETE` would be stepped around. A host in `enforce` mode is
not affected.

### Startup fails because the memberships on refresh are not chosen {#startup-fails-because-the-memberships-on-refresh-are-not-chosen}

```text
cosec.authentication.login.refresh.path=/auth/refresh serves the refresh route, and nothing says how a refreshed token gets its memberships. Without a choice it would copy the memberships of the token it replaces: a role removed from a member keeps working until that member signs in again, up to cosec.jwt.session.lifetime=30d as long as they refresh within cosec.jwt.token-validity.refresh=7d. Choose one: declare a MembershipRefresh<RefreshTokenCredentials> bean ... or state that refreshed tokens keep the memberships of the token they replace ... To keep the token's roles add cosec.jwt.session.memberships-on-refresh=from-token ... It is not set (STA-8, ...).
```

The starter serves the refresh route and has no way to know whether a removed role may keep working for the rest of the
session (`STA-8`). Decide, then write it: declare a `MembershipRefresh<RefreshTokenCredentials>` bean that reads the
member's roles again at each refresh (a removal applies within `cosec.jwt.token-validity.access`), or write
`cosec.jwt.session.memberships-on-refresh=from-token` to keep the memberships of the token being replaced (a removal applies at
the member's next sign-in). `TokenRevoker.revokeSubject` ends a member's tokens at once in either case.
`It is [claims]` (or `true`, an empty value, a padded one) means the property is written with a value that is not
`from-token`. A bean over another credentials class than `RefreshTokenCredentials` is not found: declare it as
`MembershipRefresh<RefreshTokenCredentials>`. See [how long a removed role lives](../integration/authentication.md#how-long-a-removed-role-lives).

### Startup fails on an unreadable switch

```text
CoSec cannot read 2 cosec.* switches; a value it cannot read would switch the feature off without a word:
  cosec.enabled=[1]: write true or false
  cosec.pipeline.mode=[]: write enforce or gateway
Set each to one of its values, or remove it to take its default: https://cosec.ahoo.me/operations/configuration
```

A `cosec.*` switch (`cosec.enabled`, `cosec.jwt.enabled`, the other `*.enabled` ones, `cosec.pipeline.mode`,
`cosec.api-key.store`) is written with a value CoSec does not read: `1`, `on`, `yes`, an empty string (an environment
variable a template left empty, `mode:` with no value in YAML), or `true` with spaces around it. Write `true` or `false`
(`enforce` or `gateway`, `memory` or `redis`), or remove the key. The message lists every such key at once. A switch
written `false` is legitimate: its feature stays off, and the startup summary lists it
([Startup summary](./observability.md#startup-summary)).

### The log says CoSec is off {#the-log-says-cosec-is-off}

`CoSec is off: cosec.enabled=false. This application authorizes no request and nothing is protected by CoSec.` is one
WARN, logged once the application has started. Nothing CoSec wires is active: no pipeline, no token verification, no
snapshot. Remove the property, or set `cosec.enabled=true`, unless that is what you meant; a gateway still strips a
client's `CoSec-Identity`.

### Startup fails on the gateway's own handlers {#startup-fails-on-the-gateways-own-handlers}

```text
This is a Spring Cloud Gateway host: CoSec decides the requests of gateway routes only. n handler(s) of the application itself are mapped before any route and would be answered with no CoSec decision (no authentication, no policy, no rate limit): ... (STA-13, ...)
```

It names controllers or functional routes of the application, by bean name. They are mapped before any gateway route,
so CoSec never sees a request for them ([The gateway's own handlers](../integration/web.md#the-gateways-own-handlers)).
Move them to a service behind the gateway, where a route decides them. Or, for the ones something else protects, list
their bean names in `cosec.gateway.unprotected-handlers` (`COSEC_GATEWAY_UNPROTECTEDHANDLERS`); startup then names them
in one INFO line. `... lists [*]: it takes the bean names of handlers, never a pattern` means a listed name holds `*`:
write each name.

### Startup fails on a missing module

```text
A Spring MVC host needs cosec-webmvc (me.ahoo.cosec:cosec-webmvc) on the classpath: add it to the application's dependencies (STA-1).
```

The starter wires only the CoSec modules on the classpath, so a forgotten one would silently switch a protection off.
It refuses to start instead, naming the artifact, when a feature that is on needs a module that is missing:

| Message starts with | Add |
|---|---|
| `A Spring MVC host needs cosec-webmvc` | `cosec-webmvc` |
| `A WebFlux host needs cosec-webflux` | `cosec-webflux` |
| `A Spring Cloud Gateway host needs cosec-gateway` | `cosec-gateway`; checked even with `cosec.enabled=false`, since its filter strips a client's `CoSec-Identity` header |
| `cosec.api-key.store=redis needs cosec-cocache` | `cosec-cocache` with what it needs ([Modules](../guide/introduction.md#modules)), or use `store=memory` |
| `cosec-cocache needs a CacheRuntime bean` | `me.ahoo.cocache:cocache-spring-boot-starter` ([Modules](../guide/introduction.md#modules)) |
| `cosec.audit.kafka.delivery=required needs cosec-kafka` | `cosec-kafka` with `spring-boot-starter-kafka`, or set [`delivery`](./configuration.md#audit) to `best-effort` |
| `cosec.audit.kafka.delivery=required, but no Kafka audit listener was created` | The module is there but the listener is not: a `KafkaOperations` bean (`spring-boot-starter-kafka` and its `spring.kafka.*` settings), `cosec.audit.enabled=true` and `cosec.audit.kafka.enabled=true` |
| `cosec.cosid.generator needs cosec-cosid` | `cosec-cosid`, with `cosid-spring-boot-starter` 5.x and its `cosid.*` generators, or remove the key to keep CoSec's own ids |
| `cosec.cosid.generator is set, but CoSec's ids do not come from CosId` | A `CosIdRuntime` (`cosid-spring-boot-starter` and its `cosid.*` settings), or remove the key: with an `IdGenerator` bean of your own, that bean gives CoSec its ids |
| `cosec.metrics.enabled=true needs cosec-micrometer` | `cosec-micrometer`, or remove the key: unset, metrics are optional |
| `cosec.control.enabled=true needs cosec-control` (or `cosec-cocache`) | the artifact it names, or remove the key: unset, an instance without the modules is a data-plane instance |
| `Social sign-in, whose states are kept in Redis ..., needs cosec-cocache` | add `cosec-cocache` (it keeps the OAuth states of a sign-in in Redis, once each), declare a `SocialStateStore` bean of your own, or turn social login off with `cosec.authentication.login.social.enabled=false` |
| `cosec-cocache needs a ReactiveStringRedisTemplate bean` | `spring-boot-data-redis` and a Redis connection (`spring.data.redis.*`): without it no snapshot is read and rate limits would count per instance |

The web checks run only while CoSec is on (`cosec.enabled`, default `true`); a gateway host is exempt from the WebFlux
check, as it needs `cosec-gateway` instead. Every check runs with `spring.main.lazy-initialization=true` too (`STA-3`).

### Startup fails on the refresh route's authentication {#startup-fails-on-the-refresh-routes-authentication}

```text
cosec.authentication.login.refresh.path is set, but the RefreshTokenAuthentication bean refreshes <your credentials class>, not the pair the served route presents ... (IDN-25).
```

The starter serves the refresh route with your `RefreshTokenAuthentication` bean, but yours takes a credentials class
of your own (a device id beside the pair, say), which the served route cannot build from the request. Serve that
refresh from a route of your own and unset `cosec.authentication.login.refresh.path`, or declare it over
`RefreshTokenCredentials` ([Refreshing tokens](../integration/authentication.md#refreshing-tokens)).

### Startup fails on an exposed actuator endpoint

```text
Actuator endpoints [gateway] are exposed over HTTP on the gateway's application port, where CoSec does not authorize them: anyone who reaches the gateway reaches them, and [gateway] writes its routes. ... (STA-2, https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-an-exposed-actuator-endpoint).
cosec.gateway.unprotected-endpoints lists [gateway], which it never accepts: that endpoint writes the gateway's routes. ...
```

On a Spring Cloud Gateway host, CoSec authorizes the gateway's routes only, and its actuator endpoints are on the
application port unless `management.server.port` names another. The first message lists the endpoints other than
`health` exposed there. Do one of:

- serve actuator on its own port, behind a network policy: `management.server.port=8081` (any port but `server.port`);
- expose fewer endpoints: `management.endpoints.web.exposure.include=health`;
- list the endpoints something else protects in
  [`cosec.gateway.unprotected-endpoints`](./configuration.md#gateway), such as `prometheus` behind a network policy.

The second message: `gateway` changes routes, so it is never accepted there; serve it on a management port, or not
at all ([Actuator on a gateway](../integration/web.md#actuator-on-a-gateway)).

### Startup fails on the client address

```text
server.request.header.forwarded.strategy=framework: Spring's ForwardedHeaderFilter and ForwardedHeaderTransformer make the leftmost X-Forwarded-For entry, which the client writes, the remote address ... (https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-the-client-address).
cosec.authorization.remote-ip.max-trusted-index is set, but Tomcat's RemoteIpValve resolves the client address on this host ...
cosec.authorization.remote-ip.max-trusted-index=0 reads the connection's peer, but with the web-server forwarded-header strategy ... the peer cannot be read. ...
The web-server forwarded-header strategy is on (configured or inferred from the cloud platform), but CoSec cannot tell how this host's server resolves the client address ...
```

CoSec reads the client address once, from one party it trusts (`PIP-16`), and refuses to start where it cannot tell
which. The web-server strategy may be on without your setting it: Spring Boot turns it on by itself on Kubernetes and the
other cloud platforms it detects. Per message:

- `framework`, or a `ForwardedHeaderFilter` or `ForwardedHeaderTransformer` bean that rewrites the request: use
  `server.request.header.forwarded.strategy=web-server` and configure the proxies on the server, or `none` and set
  `cosec.authorization.remote-ip.max-trusted-index`. A filter that only removes the headers (`removeOnly`) is accepted.
- Tomcat with `web-server`: remove `max-trusted-index`; Tomcat's `server.tomcat.remoteip.internal-proxies` and
  `trusted-proxies` say which proxies it trusts. Or set `server.request.header.forwarded.strategy=none` to have CoSec read
  `X-Forwarded-For` itself.
- Reactor Netty or Jetty with `web-server` and `max-trusted-index: 0`: set the index to the number of proxies in front of
  the host, or `server.request.header.forwarded.strategy=none` on a host clients reach directly.
- A server CoSec cannot tell, such as a war in an external container: set `server.request.header.forwarded.strategy=none` and the
  index.

The table is in [Client IP behind proxies](../integration/web.md#client-ip-behind-proxies).

When `cosec.authorization.remote-ip.max-trusted-index` is not set and the default `1` is in effect, the startup line
that names the reading says so, and what it risks: a host that clients reach directly, with no proxy that writes
`X-Forwarded-For`, lets them choose their own address. Set the index to the number of proxies in front of the host, or
`0` when there are none.

### Startup fails on the eviction check

```text
Redis may evict CoSec's policy snapshot: maxmemory-policy is {<node>=<policy>}, not noeviction, so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
Cannot tell whether Redis may evict CoSec's policy snapshot (Redis refused INFO (...): grant the Redis user +info), so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
Cannot tell whether Redis may evict CoSec's policy snapshot (Redis did not answer INFO memory in 5 attempts (last: ...): a timeout or a connection failure, not a missing permission; ...), so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
```

The first: a Redis node is not `noeviction`. Set `maxmemory-policy noeviction` on every node. The others: the
instance could not read the policy, and the parentheses say why.

- `Redis refused INFO`: the Redis user may not run `INFO`, or a managed Redis denies or renames it. Allow `INFO`
  (`+info`), or, when you have set `noeviction` yourself, set
  [`cosec.authorization.snapshot.eviction-check=warn`](./configuration.md#snapshot). A refusal is not asked again.
- `Redis did not answer INFO memory in 5 attempts`: each read timed out, lost its connection or got a passing reply
  (`LOADING`, `BUSY`, a failover), though the check asked five times, waiting 0.5, 1, 2 and 2 seconds in between, as
  CoCache's own check does. Redis is unreachable, or the instance was starved of CPU while it started (many JVMs
  starting on one host). This is not a permission to grant.
- `INFO memory failed (...)`: any other failure, named.
- It also names a CoCache backend the check cannot read (a wrapper or a custom backend), and a cluster connection that
  knows no node to ask (`no Redis node answered INFO memory`).

A policy the check could not read is never taken as safe ([Redis requirements](./storage.md#redis-requirements)). The
periodic re-check names the same reasons in its `ERROR` line, but reads once per interval: its retry is the next
interval, so a timeout there reads `Redis did not answer INFO memory (...)`, with no count of attempts.

With `warn` the instance starts and logs the same text at `WARN`, starting with `!!!` and ending with
`Starting anyway (eviction-check=warn).` and what eviction would do. That line is expected with `warn`; if Redis really
evicts, an app or tenant document loaded after it was evicted is read as absent, and its DENY statements stop applying.

CoCache 8 checks its `Store`s too, and `eviction-check=warn` does not reach that check:

```text
Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches [...] keep their only copy in Redis: Redis refused INFO (...). Grant the Redis user +info, or assume the policy of your deployment. Set it with cocache.redis.assume-maxmemory-policy.
Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches [...] keep their only copy in Redis: Redis did not answer in 5 attempts (last: ...). This is a timeout or a connection failure, not a missing permission: check that Redis is reachable and that this process is not starved of CPU while it starts. Or assume the policy of your deployment with cocache.redis.assume-maxmemory-policy.
Authoritative caches [...] keep their only copy in Redis, but maxmemory-policy {...} can evict them when memory is full. Use noeviction, or a volatile-* policy when they have no ttl.
```

The first: Redis refused `INFO`. Allow it (`+info`), or set `cocache.redis.assume-maxmemory-policy: noeviction` once
you have set `noeviction` yourself. The second: Redis did not answer, though CoCache asked up to five times, waiting
longer each time: the instance could not reach Redis, or was starved of CPU while it started (many JVMs starting on one
host). Make Redis reachable, or give the instance room to start; this is not a permission to grant. A third form,
`INFO memory failed (...)`, names any other failure. The last: an `allkeys-*` policy, which no setting accepts; set
`noeviction` ([Redis requirements](./storage.md#redis-requirements)).

### Startup fails on a locked cache setting

```text
Cache[PolicySnapshotCache] - ... locked by cosec ...
```

A `cocache.caches.[<CacheName>].*` entry, or a `CacheSpecCustomizer`, sets a field CoSec locks on its caches, such as
the `ttl` or `unknown-read` of the snapshot store, the `absent-ttl` of a revocation table, or a `key-prefix`. Remove the setting, or exclude CoSec's caches from
the customizer (`supports(cacheName)`). `unlock` lets the entry start, but the fields CoSec constrains keep their values
([Precedence](./configuration.md#precedence)).

```text
cocache.caches sets unknown keys [cocache.caches.[PolicySnapshotCache].ttl-forever]: misspelled, or not a per-cache key.
```

CoCache's own check: the entry sets a key CoCache does not define, or one that does not apply to that cache. Fix the
key; `cocache.caches-validation: warn` logs it instead. An entry that sets a locked field fails whatever that says.

```text
cocache.caches configures undeclared caches [ChangeRequestCach]; declared caches: [...]
```

The entry names a cache no one creates, usually a misspelled name. CoSec publishes the name of every cache it may
create, so an entry for a CoSec cache this instance does not create (a control-plane cache on a service without the
control plane) starts; fix the name.

```text
CoSec reads PolicySnapshotCache read-only in this process, which does not run the control plane: a cocache.* property or customizer cannot make it read-write (SNP-18).
```

A `cocache.caches.PolicySnapshotCache.read-only` or `cocache.caches.CandidateCache.read-only` entry, or a customizer,
changes what CoSec declares: read-only on an instance that runs no control plane, read-write where it runs
(`... cannot make it read-only`). Remove it; the classpath and `cosec.control.enabled` decide
([Who may write which keys](./storage.md#who-may-write-which-keys)).

### Startup fails on a Redis permission {#startup-fails-on-a-redis-permission}

CoCache checks the user's permissions when it creates CoSec's caches, and names what is missing. On a data-plane
instance, one that does not run the control plane, the documents and the candidates are read-only stores
([Who may write which keys](./storage.md#who-may-write-which-keys)):

| Message contains | Add to the instance's Redis user |
|---|---|
| `Read-only store of Keyspace(...): Redis denied the command [evalsha_ro]: allow [+evalsha_ro]` | `+evalsha_ro +eval_ro`: a user whose commands are restricted, such as 8.0.0-beta.1's example ([Upgrading](./upgrading-previews.md#from-8-0-0-beta-1)) |
| `allow the key pattern [%R~<s>:snapshot:*]` (or `<s>:candidate:*`) | read permission on the documents or the candidates |
| `allow the channel pattern [&<s>:inv:*]` | the invalidation channels: `&<s>:*` |

```text
Redis denied publishing to the invalidation channel[cosec:inv:snapshot] of Keyspace(...): allow the channel pattern [&cosec:inv:*] ... (atomic broadcast also needs the key pattern [~cosec:snapshot:*]) ...
```

The process runs the control plane (`cosec-control` is on its classpath), so it writes the documents, but its user may
not: usually a data-plane user given to a process that has `cosec-control`. Give the control plane a user that writes
`<s>:*`; on a data-plane instance whose image carries the control plane, set `cosec.control.enabled=false`
(`COSEC_CONTROL_ENABLED=false`), or leave `cosec-control` out of its image.

```text
cosec.control.enabled must be true or false, not [flase].
```

The key is read strictly, whatever the classpath: a value that is neither is never taken as on or as off. Write `true`
or `false` ([Configuration](./configuration.md#control-plane)).

### Startup fails on overlapping cache keys

```text
Cache[PolicySnapshotCache] - its storage keys (cosec:snapshot:…) overlap those of Cache[<other>] (cosec:…): one prefix starts the other, ...
```

Two caches of one application would write the same Redis keys: one storage prefix (`{key prefix}{namespace}:`) is, or
starts, the other. CoSec's own caches never overlap each other, so the other cache is the application's: a CoCache cache
under CoSec's [`cosec.key-prefix`](./configuration.md#key-prefix), or a `cosec.key-prefix` such as `cocache` that puts
CoSec's caches under the application's. Give the application's cache another prefix or namespace: `cosec.key-prefix`
is where every instance, the control plane and `cosec-migrate` find CoSec's data, so it changes only for all of them
at once ([Redis keys](./storage.md#redis-keys)).

### Startup fails on a channel permission

```text
Redis denied publishing to the invalidation channel[<channel>] of <cache>: allow the channel pattern [<pattern>] in the ACL of this connection's user (atomic broadcast also needs the key pattern [~<prefix>*]). Without it every write would fail at its broadcast after changing L1. Redis replied: <Redis's error>
```

A Redis ACL that denies a cache's invalidation channel fails the cache's creation with this `IllegalStateException`
(CoCache's text). A cache's channel is its storage path with `inv:` before the last segment: the pattern to allow is
the path up to its last `:`, then `inv:*`. With the default `cosec.key-prefix`, the caches CoSec creates need
`&cosec:inv:*` (snapshots, memberships, candidates), `&cosec:token:inv:*`, `&cosec:token:subject:inv:*`,
`&cosec:control:inv:*` and `&cosec:shadow:inv:*`. Allow the channels under CoSec's prefixes, and CoCache's probe in
`pubsub` mode ([Redis requirements](./storage.md#redis-requirements)).

### Startup fails because Redis is read from replicas {#startup-fails-because-redis-is-read-from-replicas}

```text
CoSec's Redis template [stringRedisTemplate] reads from replicas (ReadFromReplicaPreferred): a replica lags the primary, and CoSec reads Redis as its store of record. ...
```

The Lettuce client behind the Redis template CoSec injects, the one the message names, reads from replicas. CoSec
injects the only `StringRedisTemplate` and `ReactiveStringRedisTemplate`, or the `@Primary` one of each. Remove
`spring.data.redis.lettuce.read-from`, set it to `upstream`, or remove the `ReadFrom` your
`LettuceClientConfigurationBuilderCustomizer` sets on that template's factory. To read other data from replicas, give
it a template and connection factory of its own that is not the one CoSec injects (not `@Primary`)
([Redis requirements](./storage.md#redis-requirements)).

### Startup fails on macOS with extended TCP keepalive options {#startup-fails-on-macos-with-extended-tcp-keepalive-options}

```text
Extended TCP keepalive options could not be applied. Native transports (io_uring or epoll) or a compatible NIO transport are required.
```

Only on macOS, where Lettuce picks its kqueue transport: kqueue cannot apply the extended TCP keepalive options that
Lettuce is asked for (CoCache's Redis backend asks for them), so the Redis connection fails and the application does
not start. Linux, and with it the gateway image and every Kubernetes node, uses epoll or io_uring and is not affected.
For a run on macOS, make Lettuce use NIO with the system property `-Dio.lettuce.core.kqueue=false`, for example
`JAVA_TOOL_OPTIONS=-Dio.lettuce.core.kqueue=false`. Do not turn the keepalive options off to get around it: they are
what ends a Redis connection that died silently.

### Startup fails on the revalidation interval

```text
cosec.authorization.snapshot.revalidate-interval (PT0S) must be positive: the periodic revalidation cannot be turned off.
```

The periodic revalidation is the only bound on a document change whose invalidation message was lost (`SNP-6`), so it
cannot be off or unbounded. The message says which bound the value breaks: at least 1 second (each pass reads every
installed document from Redis), at most 5 minutes, and with `fail-closed.tenants` less than
`fail-closed.max-lag`
([Configuration](./configuration.md#snapshot)). To spend less Redis CPU on many documents, raise it within those
bounds ([Storage](./storage.md#what-revalidation-costs-redis)).

### Startup fails on the key prefix {#startup-fails-on-the-key-prefix}

```text
cosec.key-prefix [cosec:prod] holds ':': a key prefix is letters, digits, '_', '.' and '-' (cosec), or such a string wrapped whole in braces, a Redis Cluster hash tag ({cosec}), so that no deployment's prefix nests inside another's (SNP-9). ...
```

A deployment's prefix must not be able to nest inside another's: with `a` and `a:b` the second's keys are also under
the first. The message says what to do. A trailing `:` (`cosec:`), which earlier previews ignored, names the same keys
without it: remove it. A `:` inside the prefix (`cosec:prod`) names keys that are not under any prefix allowed now: move
them as the [upgrade note](./upgrading-previews.md#from-8-0-0-beta-7) says. Braces count as a hash tag only when one
pair wraps the whole prefix.

### Startup fails on a removed key prefix

```text
CoSec does not read 1 cosec.* property, which would do nothing:
  cosec.authorization.snapshot.key-prefix
Remove or rename them: https://cosec.ahoo.me/operations/upgrading#removed-and-renamed-properties
```

The environment still sets `cosec.authorization.snapshot.key-prefix`, `cosec.authorization.cache.key-prefix` or
`cosec.api-key.key-prefix`, which [`cosec.key-prefix`](./configuration.md#key-prefix) replaced (`SNP-9`). It fails like
any [unknown key](./configuration.md#unknown-properties), but renaming it is not enough: the old layout may keep token
revocations and API keys under another prefix than the one now read, and a revocation nobody reads lets the token it
revoked through again. Work out [which families move](./upgrading-previews.md#from-8-0-0-alpha-2), copy the token revocations and
the API keys to their new keys first (`COPY`, or `RENAME`, which keeps their expiry), set `cosec.key-prefix` to where
the snapshot documents are, then remove the old properties.

### Startup fails without a token store

```text
CoSec's tokens are on, but TokenStore, RevocationReader, TokenFamilyStore are missing. Verifying and refreshing tokens needs three stores: a TokenStore records revoked tokens, a RevocationReader reads them for every token verified, a TokenFamilyStore keeps the refresh token families. Add cosec-cocache, which brings all three, or a bean of each missing one, or set cosec.jwt.enabled=false. RevocationReader.NONE accepts revoked tokens until they expire: use it only where no one revokes a token, on this service or on the one that issued it.
```

Token revocation is always on with JWT, and the starter never revokes into nothing. The message names every store
missing at once. Add `cosec-cocache`, register a `TokenStore`, a `RevocationReader` and a `TokenFamilyStore` bean of
your own, or turn JWT off in a service that neither issues nor verifies tokens.

A service that only verifies another issuer's tokens, with a `TokenVerifier` bean of its own and no `cosec.jwt.secret`, has
tokens on too (the verifier is the switch) and needs only the reader. Without one it fails with `A TokenVerifier bean turns
token authentication on, and every token it verifies is checked against revoked tokens, but no RevocationReader is
available.`: add `cosec-cocache` on the issuer's Redis, a `RevocationReader` bean of your own, or `RevocationReader.NONE`
where no one revokes a token.

A service that verifies tokens over `RevocationReader.NONE` starts, and logs once at `WARN`:

```text
Tokens are verified against RevocationReader.NONE: a revoked token (logout, revoked sessions or subjects) is accepted here until it expires. Where the issuer revokes tokens, read its revocations: cosec-cocache on the issuer's Redis with the issuer's cosec.key-prefix, or a RevocationReader of your own.
```

`NONE` is right only where no one revokes a token, here or on the service that issued it. A service that verifies
another service's tokens reads that issuer's revocations
([A service that verifies tokens another service issues](../scenarios/resource-server.md)).

### Startup fails with a JWT secret error

`Property [cosec.jwt.secret] must not be blank when JWT is enabled.` Set
[`cosec.jwt.secret`](./configuration.md#jwt) to a long random value from a secret store, or set
`cosec.jwt.enabled=false`.

`Property [cosec.jwt.secret] has <n> bytes, and HMAC256 needs at least 32 (RFC 7518 §3.2).` The secret is shorter than
the algorithm's hash: 32 bytes (in UTF-8) for `hmac256`, 48 for `hmac384`, 64 for `hmac512`. Set a longer random
secret on the issuer and on every service that verifies its tokens; the tokens signed with the old one stop working.

### Startup fails on the route map

`classpath:cosec-routes.json exists but cosec.authorization.routes.location is not set, so it is not loaded: set it to classpath:cosec-routes.json, or to empty to run without a route map.`
A route map is loaded only when [`cosec.authorization.routes.location`](./configuration.md#authorization) names it, so a
file at the default location with the property unset would be left out without a word. Set the property to that
location, or to empty to authorize every request in the `http` namespace.

`The route map [<location>] (cosec.authorization.routes.location) does not exist; set the property empty to run without one.`
The property names a location with nothing there. Add the route map (the `cosecServiceGenerator/routes` actuator
endpoint generates one from the OpenAPI document springdoc scans), or correct the property.

A route map that does not parse, or has no routes (`<location> $.routes: has no routes`), fails startup too, naming its
location.

The startup log says what a host loaded: `Route map [<location>]: service [<service>], <n> routes.` for each map, or
`No route map: requests are authorized as http:<METHOD> on http:path:<path>.`

### Startup fails on the shadow instance id

`An instance id has only letters, digits, '.', '_' and '-': [<id>].` Set
[`cosec.authorization.shadow.instance-id`](./configuration.md#shadow-windows) to such an id, or leave it empty for a
random one.

### Startup fails on API keys

- `API keys are enabled, but no ApiKeyReader is available for the store <class>: with cosec.api-key.store=redis, a ReactiveStringRedisTemplate is needed.`
  Add `cosec-cocache` with Spring Data Redis, or register an `ApiKeyReader`.
- `cosec.api-key.enabled=true needs a MembershipProvider bean: an API key's memberships are its principal's ...
  (IDN-6).` A key's memberships come from its principal's provider. Add `cosec-cocache` and a `MembershipLoader`
  bean, which configures `CoCacheMembershipProvider`, or declare a `MembershipProvider`. The check runs only in
  `cosec.pipeline.mode=enforce`, where keys are read; a service in `gateway` mode reads none. On the `cosec-gateway-server`
  image the message ends with `The cosec-gateway-server image ships none, so as shipped it cannot use API keys`: the
  image has no `MembershipProvider` bean, so API keys need an image derived from it that adds one.
- `API key [<id>] needs the SHA-256 hex of the key as hash.`, `... needs a principal-id.`, `... needs a tenant-id.`,
  `Duplicate API key ids: [...].` Fix [`cosec.api-key.keys`](./configuration.md#api-keys).
- `API key [<id>] needs exactly one of roles (its cap; [] for no roles) and uncapped: true, not neither` (or `not
  both`): say what the key holds, its cap or `uncapped: true`; a key never becomes uncapped by omission.
- `cosec.api-key.enabled=[<value>] is neither true nor false.`: write `true` or `false`.
- A binding error naming `cosec.api-key.keys[n].policies` or `.attributes`: keys no longer carry them. Grant the
  principal its roles through the provider and narrow the key with `roles`
  ([API keys narrow, and memberships decide](./upgrading.md#api-keys-narrow-and-memberships-decide)).

### A gateway restarts once right after it is deployed {#a-gateway-restarts-once-right-after-it-is-deployed}

The pod's restart count is `1` (or a few) right after `kubectl apply -k`, and `kubectl logs <pod> --previous` ends with
`RedisConnectionException: Unable to connect to redis/<unresolved>:6379` (or your Redis's address). The overlay is
applied whole, so the gateway can start before Redis is reachable or before its Service's name resolves. CoSec does
not start without its store of record: startup fails closed, the container exits, and the kubelet starts it again until
Redis answers. The container that then starts is the gateway; nothing needs fixing. A restart count that keeps growing
is Redis that stays unreachable: its address in the `cosec-gateway-redis` ConfigMap, its credentials, or a network policy
between them. A restart after the gateway was live is something else ([Probes](#probes-answer-401-or-503)). CoCache
connects to Redis when its backend is created and does not start without it; to avoid the one restart, start the
gateway after Redis is ready, as [CoCache's operations guide](https://cocache.ahoo.me/operations/) describes (an init
container that waits for the Redis port, or a separate deploy step).

### Other startup failures

| Message | Fix |
|---|---|
| `STA-5: <n> CorsConfigurationSource beans of the application: the CORS filter before CoSec takes one.` | Keep one `CorsConfigurationSource`, or declare a `CorsFilter` bean of your own, which the starter then leaves in place ([CORS](../integration/web.md#cors)). |
| `cosec.audit.kafka.delivery=[<value>] is neither required nor best-effort.` | Write `required` or `best-effort` ([`delivery`](./configuration.md#audit)); case, spaces and `-` for `_` do not matter. |
| `Decision listener <name> is registered twice.` | Give each `DecisionListener` its own `name`. |
| `CoSec's <CacheName> is the store of record: a Store.` | Something replaced the spec of one of CoSec's caches; CoSec creates them itself. |
| `revalidateInterval ...` and the other snapshot option checks | Fix [`cosec.authorization.snapshot.*`](./configuration.md#snapshot). |
| `Invalid change options.`, `Invalid shadow window options.` | Fix [`cosec.control.*`](./configuration.md#control-plane): `break-glass.max-duration` above `24h`, or `shadow.max-duration` below `shadow.min-duration`. |
| `cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created: it needs cosec-kafka, a KafkaOperations bean and cosec.control.audit.kafka.enabled=true.` | Add what it names, or set [`delivery`](./configuration.md#control-plane) to `best-effort`. |
| `The control plane checks its approvers' revocations, but no revocation reader is available: ...` | Enable CoSec's tokens with `cosec-cocache`, or declare a `RevocationReader` bean; `RevocationReader.NONE` only when no approver's credential is ever revoked ([Control plane](./configuration.md#control-plane)). |
| `A token revocation is kept for <d>: the longer of cosec.jwt.token-validity.access (<d>) and cosec.jwt.token-validity.refresh (<d>), plus cosec.authentication.revocation.skew (<d>), shorter than cosec.control.approval.expire-after (<d>): ...` | Raise a token validity or lower the approvals' expiry: a revocation must outlive every approval its token gave. |
| `cosec.authentication.login.refresh.path is set on a Spring Cloud Gateway host. ...` | A gateway issues no sessions: unset it there, and set it on the service that issues them, behind the gateway. |
| `... is set, but this application serves no HTTP ...`, `... is set, but login or JWT is off ...`, `... is not one plain absolute path ...`, `... name one path ...` | Unset the [login route path](./configuration.md#authentication), or give the host what it names: a web stack, `cosec.authentication.login.enabled` and `cosec.jwt.enabled`, one plain path per route. |

## Control-plane errors

The request to the control API goes through the host's pipeline first, like any request, so the pipeline's own
`401`, `403`, `429` ([per-tenant limits](../policies/rate-limiting.md#the-control-api)) and `503` apply to it. Then the control plane authorizes each action again as that principal, in the app
[`cosec.control.app-id`](./configuration.md#control-plane). Its problem bodies use the same `type` prefix, and a
`400`, `409`, `410`, `412`, `422` or `428` explains the request in `detail`; a `401`, `403`, `404`, `500` or `503` never
does, so a refusal does not tell whether a document, a change or a DENY exists. The reason of a refused step is in the
control host's log instead, at `INFO` from `me.ahoo.cosec.control.api`:
`Control API <method> <path> by <tenant>/<principal> is <status> (request <requestId>): <reason>`, with the
`requestId` of the problem body, such as `a workload's credential (an API key, a service) cannot approve: an approval is a person's` or `only a platform
principal sets a tenant's boundary`.

| Status | Cause | Fix |
|---|---|---|
| `401` | No principal the pipeline permitted stands behind the request: anonymous, or the route is not served behind the pipeline. Nothing of its body is read. | Send a credential. The documents must permit the HTTP request to `cosec.control.http.base-path` for this principal. |
| `403` | The operator may not take this action on this document (for `simulate` and `explain`, on the tenant the request is decided in: `(0)` for one without a `principal`), or the hard rules forbid it: the author approving their own change, someone else rebasing it, or anyone but its author abandoning a revoke of a live break-glass binding (`BRK-10`). | Bind the operator a control-plane policy (`cosec:platform-admin`, `cosec:tenant-admin`, ...) for that document ([Testing changes](../policies/testing.md)). |
| `400` | The body is not a JSON object, a field is missing or unknown, a value has the wrong type or does not parse (a time, a mode, a number), or (WebMvc) the body is larger than 10 MB (the document cap and 2 MB, [computed](./configuration.md#computed-not-configured)), or larger than 64 KB on a route that carries no document (`The request body is <n> bytes, over the 65536 this route reads.`). `simulate` and `explain` also refuse a request without a `principal` that names a tenant other than `(0)`, which enforcement would decide on `(0)`; one that repeats, with different values, a header a document of its view reads, as enforcement refuses it; and an `http` path enforcement refuses (a dot segment, a malformed escape, over 2,048 characters as received or 256 per segment once decoded) or a typed id over those lengths as written, which `analyze` refuses too. Any request whose path, query or body holds a string with an unpaired UTF-16 surrogate (a JSON escape such as `"\uD800"`) is refused before its route reads it: `The request holds a string with an unpaired UTF-16 surrogate, which no id may hold (IDN-10).` | Fix the request; `detail` says what, in the control plane's own words or by the field's name, never an exception's message. A `400` without `detail` is logged at `INFO`: `Control API <method> <path> (request <requestId>) is 400: <reason>`. On WebFlux a large body is bounded by `spring.codec.max-in-memory-size`. |
| `404` | No such route, document or change, or one the operator may not see. A path with a trailing `/` or an empty segment (`//`) is no route. | Use the route's exact path. |
| `409` | The base moved (`[<doc>] is at revision <n>, not <m>`), the change is in another state, another change holds the document's staging slot (only a break-glass revoke, or a change that only narrows by a holder of `cosec:skip-shadow`, takes it, and only another revoke of a live binding takes a revoke of a live binding's: [Taking a busy slot](../policies/testing.md#taking-a-busy-slot)), the shadow window is open or blocked, or a break-glass revoke did not take effect (`binding [<id>] still grants: change <id> is <state>`, below). | Rebase, wait for the other change, read the window's summary, or revoke again. |
| `409` from `:apply` with `REPLACE`: `[<doc>] is at revision <n>, not <m>: REPLACE removes what the file leaves out, and the file was read at revision <m>` | The file's header names the revision it was read at, and the head moved since: applying it would remove what was published in between. | Read the head (`GET /documents/<doc>`), bring what changed into the file, set its `revision` to the head's and apply again ([Changes](../policies/testing.md#changes)). |
| `422` from a publish: `publishing change <id>: after this change <principal>@<tenant> could not reach the control plane: …` | The change to the platform document, the platform tenant's or the control app's would leave the publisher unable to reach the control plane: the reason names the request and what denies it (`V4`). | Change the change, or acknowledge it with `"lockout": true` in the publish's body when it is a handover ([Publishing and rolling back](../policies/testing.md#publishing-and-rolling-back)). |
| Every control-plane call answers `401` or `403` after a change to the platform document | A published change keeps the control API's path closed, or takes away the grants of every operator: nobody can change it back through the control plane. | `cosec-migrate restore --document platform --revision <the last good one>`, then `--confirm` ([Recovering from a lock-out](./storage.md#recovering-from-a-lock-out)). Do not empty Redis. |
| `503` on one change, or `Could not read the open changes; no revision is reclaimed this pass.` at every maintenance pass | The change's record does not decode: it is of an app that cannot be an app id (blank, `*`, `:`, `/`, a control character, over 128 characters, `SCP-10`, `SCP-13`), or an alpha wrote it in a shape this version no longer reads, such as an approval without `principalBindings`. It can be neither shown nor abandoned, and until it is removed nothing is reclaimed. | Remove its record from the control store (with `cosec-cocache`, the key `<key-prefix>:control:change:<id>`), as [Upgrading](./upgrading-previews.md#from-8-0-0-alpha-3) says. |
| A document a preview published is not in `GET /documents`, logged `The documents list names [tenant:<id>], which is no document key: left out.`, and `/documents/tenant:<id>` answers `400` `That names no document` | Its tenant or app id holds `/` (`acme/west`), which no 8.0 id can (`SCP-13`): no request reads it, and the control plane can name it no more. | Rename the tenant or app, publish its document under the new id, then remove the old keys ([Upgrading from a preview](./upgrading-previews.md#from-8-0-0-alpha-4)). |
| `503` from the control plane (a change, a publish, `simulate`, the maintenance pass or the shadow windows), logged with a format error that names a record and an id with `/`: `Change [<id>] does not read: … Tenant id [acme/west] must not …`, `The back-reference set of [<doc>] policy [<id>] does not read: … [tenant:acme/west] is not a document key`, `The status of instance [<id>] does not read: …` | An 8.0 preview stored a record under a tenant or app id with `/`, which this version refuses (`SCP-13`). The record fails whole and closed (`SCP-10`); nothing skips it. | Remove the records of that id, as [Upgrading from a preview](./upgrading-previews.md#from-8-0-0-alpha-4) lists them: the preview's control plane cannot. |
| `503` from the control plane, or a publish that fails, with `The back-reference set of [<doc>] policy [<id>] is of the format before 8.0.0-beta.5 (…). Convert the store first: run cosec-migrate repair-references.`; at startup one `ERROR` `The control store was written before 8.0.0-beta.5`; `cosecControlStoreFormat` `DOWN` | The store was written by a control plane of 8.0.0-beta.4 or earlier: its back-references are one value per policy, which this version reads as sets and refuses (`CTL-42`). A change that touches one is refused, never let through. | Stop every control plane of an earlier version and run `cosec-migrate repair-references` once ([Upgrading](./upgrading-previews.md#from-8-0-0-beta-4)). It prints `The store is in the format of 8.0.0-beta.5.` A value no head touched stays in the old format; when a later change touches it, run the command again. |
| `GET /changes` answers `503`; the reclaim pass logs `The open-change sets are not kept (a list of the format before 8.0.0-beta.5 is there): no revision is reclaimed …` at `WARN`; revisions pile up | The store still holds the open-change list of 8.0.0-beta.4 or earlier, `<s>:control:list:open-changes`, or a control plane of such a version wrote it again: the open-change sets are not trusted to hold every open change, so no revision an open change might rest on is reclaimed (`CTL-54`). | Stop every control plane of an earlier version and run `cosec-migrate repair-references` ([Upgrading](./upgrading-previews.md#from-8-0-0-beta-4)). It prints `Listed N open change(s) in the open-change sets.`; a `FAILED change [<id>] does not read` line names a change record to remove or fix before the next run. |
| A publish, or `cosec-migrate repair-references`, fails with `The back-reference set is malformed` | The set is the JSON array of keys 8.0.0-alpha.3 wrote, or names an app that cannot be an app id. `repair-references` reads a set before it writes it, so it cannot get past one. | Stop the control plane, delete the set (`<p>:control:refs:<doc>:<policyId>`), run `cosec-migrate repair-references`, and start it again ([Upgrading](./upgrading-previews.md#from-8-0-0-alpha-3)). |
| A publish, or `cosec-migrate repair-references`, fails with `A document holds binding [<id>] in app [<app>], which no document key can name` | The document's head, written by an earlier build, holds a binding in an app that cannot be an app id. No change can publish over it. | Remove the binding before you upgrade ([Upgrading](./upgrading-previews.md#from-8-0-0-alpha-2)). |
| `422` `REFERENCES`: `[<id>] is referred to by [<doc>]`, for a `<doc>` that does not refer to it | A publish of `<doc>` indexed the reference and then lost its head write. | Run [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index). It removes the entry once `<doc>`'s head is at or above the revision that indexed it: when it does not, publish `<doc>` first. |
| `422` `REFERENCES`: `[<id>] changes between a policy and a boundary, and is referred to by [<doc>]` | A platform or app change turns a policy or a boundary into the other kind while `<doc>` refers to it (`CTL-30`), or removes it (the message is then `[<id>] is referred to by [<doc>]`); a policy an app's short form derives counts. | Move `<doc>` off it first, or define a new policy of the kind you need. |
| `422` `REFERENCES`: `[<id>] is a boundary of the platform, and a boundary is not bound` | A binding names a platform boundary. | Bind an ordinary policy; a tenant is held to a boundary through its document's `boundary`. |
| `422` `V2`: `[<id>]: an entry's id has no [/]` | A change puts a policy, binding, role or rate limit whose id holds `/`. | Rename it. Only a permission policy's `perm:<app>/<id>` holds one. |
| `410` | The revision asked for is not kept. | |
| `428` | Publishing needs an acknowledgement of the shadow window's counts. | Publish again with the `ack` of that window. |
| `503` | The authorization of the action could not be decided (no platform document, Redis unavailable), the store could not be read, an approver's revocations could not be read on publish (`the revocations of the approver could not be read` in the log; the change keeps its approval), or a `REQUIRED` change listener did not record a gated step. Break-glass without a `REQUIRED` change listener: `break-glass needs a REQUIRED change listener, and none is configured`. | Restore Redis or the listener's store ([Control-plane change log](./observability.md#control-plane-change-log)). |
| `500` | A stored document or record does not decode, such as one a newer version wrote during a rolling upgrade: `Control API <method> <path> (request <requestId>): a stored value does not decode.` at `ERROR`, the exception naming the document's key (`The stored document [<key>] revision <n> does not decode: ...`). Otherwise a defect: `Control API <method> <path> failed (request <requestId>).` at `ERROR`. | Finish the upgrade, so that every control-plane instance reads what the newest one writes. |

A change whose checks fail is not an error response: it stays `DRAFT` with its `findings`, each with a status (`422`
for a check, `412` for an `ifMatch` that does not hold).

- **`[<id>] is a policy id [tenant:...] already use`** (`V2`) on a platform change: a tenant document defines or binds
  a policy of that id, and the platform's would make it ambiguous there. Give the platform policy another id, or have
  the tenant remove or rename its own first. With `, or whose document could not be read` (`503`), a tenant document
  the index names could not be read; retry once Redis answers.
- **`[<id>] is a platform policy's id, and this policy is not an exact copy of it`** (`V2`) on a tenant change: the
  tenant's policy has the id of a platform policy and different content, which the engine would refuse as ambiguous. A
  tenant may hold only an exact copy ([Partition plan](./upgrading.md#partition-plan)); give the policy another id, or
  make it identical to the platform's. The same rule holds the platform's policy still while a tenant holds a copy:
  **`[<id>] is copied by [tenant:...]: change it only after their copies are removed`** (`V2`) on a platform change.
- **A `REVIEW_NEEDED` change event** after a publish: another document the change was checked with moved before its
  write, and the analysis on the current heads finds an escalation nobody approved. The change is published; review it
  as [What a publish compares](./storage.md#what-a-publish-compares) describes. With the escalation reason
  `DETECTION_TIMED_OUT`, the comparison after the write did not finish in time, so nothing is known to have moved;
  review the change the same way.
- **`[<doc>] revision <n> is published; its index or the references it dropped could not be updated`** at `WARN`,
  from the control plane's revision store (`me.ahoo.cosec.control.store`): a step after the head write failed, the
  revision index or the back-reference index, not the write. The revision is in effect and nothing needs doing:
  `history` rebuilds the index from the chain, and a reference left behind blocks a removal at most. If the change
  itself could not be settled, the publish answers an error although the revision is in effect; publishing it again
  reports `PUBLISHED`, finishes what followed the write and records the events as the original publisher.
- **A platform or app change that only removes entries waits in `AWAITING_APPROVAL`**, with a `NOT_ANALYZED`
  escalation `policy <id> of <doc>` or `binding <id> of <doc>` (`it removes a DENY: only the trust root does it
  alone`): the change takes a DENY away, which nothing above the platform or an app still holds (`CTL-31`). A role
  removal counts for each binding the role derived, `role:<role>@<app>:<permission>`. Have a second platform principal
  holding `cosec:approve` approve it ([Approval](../policies/testing.md#approval)).
- **An approval no longer counts at publish**: the change is `AWAITING_APPROVAL` again, its `APPROVAL` finding saying
  `the token the approver approved with is revoked`, `the approver's tokens are revoked`, `the approver no longer
  holds cosec:approve on the document`, `only a platform principal approves a boundary change`, `under four-eyes, only
  a platform principal approves a platform principal's change`, or `the approval does not say what credential its
  approver approved with` (an approval from before the upgrade, from an older instance, or by a credential without an
  id). Ask for a new approval. A `403` on `:approve` with `a workload's credential (an API key, a service) cannot approve` in the log: the approver
  authenticated with an API key or stated a service's own identity (`IDN-34`); approve with a person's token; with `an approval names the approver's credential, and this one names none`: approve with a
  credential that has an id, such as CoSec's token.
- **A service's change waits for a person although it states a platform administrator**: the change came through
  `ControlService` or the control-plane host's service port, where no one is the trust root (`CTL-81`), so it is
  analyzed: `NOT_ANALYZED` on an app's catalog, `CROSS_TENANT` on a grant in a tenant's document. Have a person approve
  it on the operators' routes, or, for roles projected into tenants, give the service the
  [role class](../policies/testing.md#the-role-class). A role change that waits with no `ROLE_CLASS`: the principal does
  not hold `cosec:manage-roles` on the tenant's document, or the role is of the control plane's own app or has no app,
  which are never the class's.
- **A `FAILED` change event on a publish that met another change at the slot**: `another change took the staging
  slot before the head write` (the change was preempted while it published; nothing was written), `the document moved
  before the head write` (the head moved after the change's last slot check, by the change that took its slot, a
  bootstrap, a migration, or an older instance, which may have been publishing this same change; nothing was written),
  `revision <n> was published over the same base first, by <id>` (another publish over the same base came first: the
  change it preempted, within one store round trip, a bootstrap, a migration or an older instance; rebase it), or
  `conflicted by <id>, which did not take the slot` (a preemption gave up after marking the holder; rebase the holder).
  A publish of this version that meets its own change's revision, written by a concurrent publish of the same change,
  records no failure.
- **A revoke answers `503` and the break-glass binding is still there**: its publish stopped (the REQUIRED change
  listener did not record `PUBLISHING`), and the revoke stays `READY` in the platform document's slot. Revoke the
  binding again once the listener's store answers: that publishes it; a revoker who may not read it revokes with a
  change of its own, which takes its slot. Revoking the principal's tokens works meanwhile. Only the revoke's author
  can abandon it (`403` for anyone else, `BRK-10`), and doing so needs the same listener (`503`).
- **`DELETE /break-glass/{bindingId}` answers `409` `binding [<id>] still grants: change <id> is <state>`**: the revoke
  did not take effect, and the binding still grants. `CONFLICTED`: other revokes or writes kept moving the platform
  document, and the revoke gave up after three more tries; revoke again, and revoke the principal's tokens meanwhile.
  `DRAFT`: read the change's `findings`.
- **`DELETE /break-glass/{bindingId}` answers `409` `binding [<id>] is not a break-glass binding`**: the platform
  document's binding of that id is an ordinary one, live or expired, and nothing was proposed. Remove it with a change
  ([Testing policies](../policies/testing.md)).
- **`DELETE /break-glass/{bindingId}` answers `409` `[platform] is at revision <n>, not <m>`**: another write moved the
  platform document just as the revoke was proposed, which may have been another call revoking the same binding. Check
  whether the binding is still there: retry (a `404` means it is gone), or read `GET /documents/platform`.
- **`DELETE /break-glass/{bindingId}` answers `404`**: the platform document has no binding of that id, and nothing
  was proposed. Check the id (`GET /documents/platform` lists the bindings). A `404` on a retry, after a first call
  that timed out or failed, means the binding was removed, by that call or another: the platform document's revision
  history shows which revision removed it. Like every `404` of the control API it carries no `detail`; the control
  host logs `the platform document has no binding [<id>]` at `INFO`.
- **A proposal is `409` with `conflict: OPEN_LIMIT`**: the document already holds `cosec.control.revisions.max-count`
  open changes (50 by default, `CTL-83`); `heldBy` names the oldest. Your own oldest change whose checks failed was
  abandoned first, so what is left is changes that can still move on, or other authors'. Abandon those you no longer
  need (`POST /changes/{id}:abandon`), publish them, or wait for them to expire (`cosec.control.changes.expire-after`).
- **`GET /changes/{id}` is `404` for a change that ended**: a document keeps its latest
  `cosec.control.revisions.max-count` ended changes, and reclamation removes older ones past
  `cosec.control.revisions.min-age` whose revision is no longer retained (`CTL-83`). Who published a revision is in the
  revision itself: `GET /documents/{key}/revisions/{revision}` names its change, author and approvers in `provenance`;
  the audit events of the change ([Observability](observability.md)) keep the rest.
- **A tenant change waits for approval with `TOO_LARGE`**: the grant analysis made the four million comparisons one
  analysis may make before it reached those grants, so what it did not compare is not known (`CTL-49`). Split the
  change into smaller ones; statements a policy already had cost almost nothing, so the size that matters is what the
  change adds or rewrites, times what its author holds. One entry `change of tenant:<t>` means the change grants more
  than 80,000 fragments (each action times each resource of a granted ALLOW, for each binding): nothing of it was
  analysed. Split the statement's actions or resources, or the bindings, over several changes.
- **Every tenant grant over every app waits for approval** (`UNREADABLE`) after an upgrade, with
  `The app list is missing or incomplete: run cosec-migrate repair-references; ...` at `WARN` (at most once a minute, at
  startup too):
  the app list is not complete yet, so the analysis cannot tell what the author holds in apps it did not read. Once
  every control-plane instance and `cosec-migrate` runs this version, run
  [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index) once. The same after the list
  was lost (a `FLUSHDB`, an eviction). `The app list misses an app document that exists`, `The app list has an entry
  that names no app document`, and, from the reclamation pass, `The app list misses the app documents [...]; it is
  incomplete again` (an older instance or `cosec-migrate` published an app) need `repair-references` too.

## Snapshot log lines are missing

An instance logs `Snapshot housekeeping since its last summary: refused <n> tasks, full or stopped …` at `WARN`, and
`cosec.snapshot.housekeeping.refused` rises. The snapshot source writes its log lines, a failed load's included, on one
housekeeping thread whose queue holds 4,096 tasks. When the queue is full it drops the line and counts it, rather than
write it on the thread of the request that caused it (SNP-19). The summary names the last line dropped for each task,
without its stack.

- **Cause.** The log appender is slower than the lines arrive: a synchronous appender writing to a slow disk or a
  remote collector, usually while many documents fail to load at once.
- **Fix.** Use an asynchronous appender (Logback's `AsyncAppender`, Log4j 2's async loggers). Requests are not slowed
  meanwhile, and decisions do not change: only log lines and eviction passes under the cap are dropped. An eviction
  pass that is dropped is asked for again at the next load, and the family never grows past its cap.
- **`meter failures`** in the summary is a meter of the bound registry that threw. It is counted there and never logged
  on its own; `last` names the event and the exception.

## A log line names an id with `\u` escapes {#a-log-line-names-an-id-with-u-escapes}

CoSec writes a control character or an unpaired UTF-16 surrogate of an id as its `\uXXXX` escape in log lines and
error messages: `Tenant id [beta\uD800] ...`, never `beta?`, which is another id. A `\uXXXX` in a line is either such an
escape or the id's own characters (a backslash, `u` and four hex digits, which an id may hold): the line that refuses an
id says why, so read the reason rather than the id.
A control character would forge a line (RC1-15); a surrogate would be written as `?`, naming another, healthy tenant
or principal (RC1-52). Such an id is refused where it enters (`IDN-10`: [`token-invalid`](#token-invalid),
[`unavailable`](#unavailable), [Control-plane errors](#control-plane-errors)), so it appears only in the line that
refuses it. Find what sent it: a token issuer, a user store, a membership provider or a script calling the control API.

A rate limit `key` whose value holds an unpaired surrogate counts in a bucket of its own, written escaped
(`e:<value>`, `\` doubled): never in the bucket of the value with `?` in its place.

## Audit events are missing

1. `cosec.audit.enabled` must be `true` (the default).
2. The audit log writes allows at `DEBUG`: set `me.ahoo.cosec.audit` to `DEBUG` to see them.
3. Events dropped by a full queue: `Dropping decision events: the queue of listener [<name>] is full; <n> dropped since the last report.` and the
   `cosec.listener.dropped` counter. A listener that throws: `Decision listener [<name>] failed.`
4. Kafka: `Failed to publish audit event to Kafka topic [<topic>]: <n> failed since the last report.` (once every 10 seconds at most), or `The Kafka audit queue of [<topic>] is full.` (`is closed.` after shutdown began)
   ([Audit log and Kafka](./observability.md#audit-log-and-kafka)).
5. At shutdown, queued events go out for up to
   a sixth of `spring.lifecycle.timeout-per-shutdown-phase` (5 seconds at the default); then no listener call
   starts, and the rest is dropped and counted
   (`Decision listeners did not deliver every queued event within <timeout>; <n> were dropped, and no listener call starts any more.`).

**Next:** move an existing deployment to a newer version, in [Upgrading](./upgrading.md).

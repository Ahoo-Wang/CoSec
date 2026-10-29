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
   `authorization.source.policy`, the `binding` it applied through, and the `snapshotVersion` it read
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

An explicit and an implicit deny give the same response; the audit event tells them apart. How outcomes map to
statuses, headers and bodies is defined once, for every adapter, in [Web integration](../integration/web.md).
Control-plane responses have more statuses: [Control-plane errors](#control-plane-errors).

### `invalid-request` {#invalid-request}

The request cannot be read without ambiguity, so it is refused before authentication. The audit event has `kind`
`BAD_REQUEST`. Causes:

- the path has `.` or `..` segments, or a malformed percent-escape;
- a `CoSec-App-Id`, `CoSec-Space-Id` or `CoSec-Device-Id` parameter has two different non-blank values, between the
  header and the query string or within either, or its query value has a malformed percent-escape;
- a route map captures a value that would change the resource id's segments;
- on a service in `cosec.pipeline.mode=gateway`, a `CoSec-Identity` header that cannot be read
  ([A service behind the gateway refuses requests](#a-service-behind-the-gateway-refuses-requests)).

Fix the client ([Web integration](../integration/web.md)). On the control API, a `400` names the problem in `detail`
([Control-plane errors](#control-plane-errors)).

### `unauthorized` {#unauthorized}

The caller is anonymous, and the request was denied: no credential was sent, or no statement allows anonymous callers
to do this. A scope rejection of an anonymous caller is also `unauthorized`.

1. **Was a credential sent where CoSec reads it?** A token in the `Authorization` header or an `authorization` cookie,
   or the `authorization` query parameter with
   [`cosec.pipeline.allow-query-token=true`](./configuration.md#pipeline); an API key in the
   [`cosec.pipeline.api-key-header`](./configuration.md#pipeline) header with `cosec.api-key.enabled=true`.
2. **Should anonymous callers be allowed here?** Then bind a statement that allows them; otherwise send a credential.

### `token-invalid` {#token-invalid}

The token, or the API key, was rejected, and the request, evaluated as anonymous, was denied. A rejected credential
is not an error by itself: if a statement allows anonymous callers, the request proceeds as anonymous.

- **Token:** the issuer and this service must share [`cosec.jwt.secret` and `cosec.jwt.algorithm`](./configuration.md#jwt).
  With `cosec.jwt.enabled=false` every token is rejected.
- **Claims CoSec refuses:** a `tenantId` claim that is not a string (a number, `null`, an object), or an
  `attributes` claim that names `apiKeyId`, which CoSec sets for API keys only. Issue the tenant as a string, and
  rename the attribute ([Authentication](../integration/authentication.md#tokens)).
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

### `token-revoked` {#token-revoked}

The token was revoked by logout, or every token of its principal issued before a subject revocation was revoked
([Authentication](../integration/authentication.md)). Sign in again. A token issued up to
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

The scope the request declares cannot be granted to this caller:

- `CoSec-App-Id` names an app other than the host's [`cosec.authorization.scope.app-id`](./configuration.md#authorization),
  or an id that cannot be an app id;
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

### `unavailable` {#unavailable}

No decision could be made, so the request was refused: CoSec fails closed. The audit event's `decision` is
`INDETERMINATE`, and `authorization.reason` names the cause; the metric tag `cause` groups them. A `503` never carries
`Retry-After`.

| `reason` / metric `cause` | Cause | Fix |
|---|---|---|
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`, on every request | No platform document is published: a new deployment, or a Redis that lost it. `cosec-migrate bootstrap` writes only the platform tenant's document. Until a platform document exists every request is `503`, the control API included. On a new deployment, readiness refuses traffic until the first one is installed ([Instance is not ready](#instance-is-not-ready)). Readiness latches once it accepts: a document lost later does not make it refuse again, so every request fails closed with `503`, and only the `cosecSnapshot` health indicator goes DOWN. That turns readiness DOWN only if you add `cosecSnapshot` to the readiness group, which during a Redis outage turns 503s into connection failures. | Publish one with `cosec-migrate publish` ([The first documents](./storage.md#the-first-documents)). |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`, for some tenants only | The tenant is in [`cosec.authorization.snapshot.fail-closed.tenants`](./configuration.md#snapshot), and the oldest document its requests read was last confirmed longer ago than `fail-closed.max-lag` (`Snapshot lag [<lag>] exceeds the fail-closed bound [<max>] of tenant [<tenant>].`). | Restore Redis; see [Decisions use an old revision](#decisions-use-an-old-revision). |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable` | A document the request needs could not be read in time (Redis down or slow on a cold load), cannot be decoded or compiled, or disappeared from Redis after it was seen. A failed load and a lost document are logged with the document's name: `Loading snapshot document [<doc>] failed.`, `Snapshot document [<doc>] disappeared from the store; failing closed.` | Restore Redis, or publish a readable revision of the document ([When Redis is unavailable](./storage.md#when-redis-is-unavailable)). |
| `INDETERMINATE: MissingPolicies`, `AmbiguousPolicy` / `missing_policies` | A binding or an attachment names a policy the documents do not define, or a policy id both the tenant and the platform define. | Fix the document through the control plane. |
| `INDETERMINATE: MembershipUnavailable` / `membership` | The `MembershipProvider` failed, or took longer than [`cosec.authorization.membership.timeout`](./configuration.md#authorization). With `CoCacheMembershipProvider`, a `MembershipLoader` that does not answer within that time fails its load (`The membership loader did not answer within ...`), and the next request tries again. | Fix the provider or its store. |
| `INDETERMINATE: AttributeUnavailable` / `attribute` | A statement needs an external attribute whose provider failed or timed out (`<provider> failed to fetch [...]` at `WARN`). | Fix the provider, or raise [`cosec.authorization.attributes.timeout`](./configuration.md#authorization). |
| `INDETERMINATE: MatcherFailed` / `matcher` | A DENY statement's condition, or the condition of its policy, could not be evaluated. That includes a DENY on `http.header.<name>` when the request sent that header more than once with different values (`Header [<name>] arrived with different values; a condition on it cannot decide.`); the same header repeated with one value reads as that value. A failing ALLOW never makes a request `503`: an ALLOW on such a header does not match. | Fix the condition, or the client that sends the header twice. |
| `INDETERMINATE: BoundaryUnavailable` / `boundary` | A statement of the tenant's boundary could not be evaluated for a permitted request. | Fix the boundary. |
| `INDETERMINATE: RateLimiterUnavailable` / `rate_limiter` | A rule with `strictFailure: true` could not be counted (`Rate limiter unavailable: rule [<id>] refuses the request.` at `ERROR`). | Restore Redis, or drop `strictFailure`. |
| `INDETERMINATE: RequiredListenerFailed` / `required_listener` | A `REQUIRED` decision listener failed or timed out on a permit (`REQUIRED decision listener [<name>] failed on request [<id>].`), or a permit came through a break-glass binding with no `REQUIRED` listener (`Request [<id>] permitted through break-glass, but no REQUIRED listener is registered.`). | Fix the listener's store, or register a `REQUIRED` listener before using break-glass ([Decision listeners](./observability.md#decision-listeners)). |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator` | The authenticator failed or took longer than [`cosec.pipeline.authentication-timeout`](./configuration.md#pipeline): for example the Redis API key store is unreachable. | Restore the store. |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`, for every request of one authenticator | An `Authenticator` of your own names its credential's method `jwt`, which is reserved for CoSec's token (`The authentication method [jwt] is CoSec's own token's ...`). | Name the method after your credential, such as `Identity.Method("ldap")` ([Extending](../architecture/extending.md#authenticators)). |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`, for every request with one key | The key is stored in the format before 8.0, which is refused when read (`The API key [<id>] is stored in the format before 8.0 ... Convert the keys with cosec-migrate api-keys`). | Convert the keys ([API keys narrow, and memberships decide](./upgrading.md#api-keys-narrow-and-memberships-decide)). |
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
- `The identity of [<subject>] is <n> bytes encoded, over the limit of <max>: ...`, `Claim [<name>] is <n> characters,
  over 1024.` or `Claim [<name>] is a <type>, not a string, number or boolean.`: fewer roles or
  [`cosec.gateway.identity.claims`](./configuration.md#gateway-identity), or a larger `max-size` on the gateway and
  the services.

## Request allowed unexpectedly

1. **Read the deciding statement** in the audit event or the explanation: `source.policy`, `binding` and
   `breakGlass`.
2. **A broad allow.** Look for an allow with `*` or `**` actions or resources, bound to `*` subjects, or without a
   condition. Add a deny, or narrow the allow.
3. **A deny that does not match.** Its action, its resource and its condition must all match. Explain the request to
   see where the deny missed.
4. **Break-glass.** `breakGlass: true` and `source.type` `ROOT` mean a break-glass binding of `cosec:root` permitted
   the request; such bindings expire on their own and can be revoked through the control plane.
5. **An old revision.** `snapshotVersion` names the revisions the decision read. If they are older than what was
   published, see [Decisions use an old revision](#decisions-use-an-old-revision).

## Decisions use an old revision

The audit event's `snapshotVersion`, or the span's `cosec.snapshot.version`, is older than the revision the control
plane published.

- **The instance cannot reach Redis.** It keeps deciding with what it has installed, and logs
  `Snapshot document [<doc>] revision <n> has not been confirmed for <lag>.` at `WARN` after `lag-warn` (60 seconds),
  `... is stale: not confirmed for <lag>.` at `ERROR` after `lag-escalate` (5 minutes). Restore Redis.
- **The instance missed an invalidation message.** It catches up within
  [`revalidate-interval`](./configuration.md#snapshot) (10 seconds). A longer gap with Redis healthy means the periodic
  pass is off (`revalidate-interval: 0`).
- **The instance reads another namespace.** Every instance and the control plane must share
  [`cosec.authorization.snapshot.key-prefix`](./configuration.md#snapshot) and the Redis.
- **Redis lost the write.** After a failover the instance may log
  `Snapshot document [<doc>] regressed from revision <a> to <b>; ...` at `ERROR`: publish the change again.

The control plane's propagation view shows how many instances have a revision installed
([Snapshot health](./observability.md#snapshot-health)).

## A service behind the gateway refuses requests

A service in [`cosec.pipeline.mode=gateway`](./configuration.md#pipeline) reads the caller from the `CoSec-Identity`
header the gateway forwards. A request without the header proceeds as anonymous; a header it cannot accept is never
read as anonymous:

| Status | Cause | Fix |
|---|---|---|
| `400` `invalid-request` | The header is not base64url JSON of format v1, has an unknown or missing member, is larger than [`cosec.pipeline.gateway.max-size`](./configuration.md#pipeline), arrived more than once, or names a scope that does not resolve on this service. | Something other than the CoSec gateway sets the header, or the gateway's `cosec.gateway.identity.max-size` is larger than the service's limit. |
| `401` `token-invalid` | A version this service does not read, no `auth.exp`, or an identity issued later than now plus [`cosec.authentication.revocation.skew`](./configuration.md#authentication). | Upgrade the service with the gateway; synchronize the clocks. |
| `401` `token-expired` | The credential expired, or the identity was issued longer ago than [`cosec.pipeline.gateway.max-age`](./configuration.md#pipeline) plus the skew. | Synchronize the clocks; a request that waits long between the gateway and the service needs a larger `max-age`. |

Only the gateway may be able to reach such a service: it believes any header it receives. Startup logs this at `WARN`.

## Instance is not ready

Readiness refuses traffic until the platform document and every document in
[`prewarm-apps` and `prewarm-tenants`](./configuration.md#snapshot) are installed; liveness is not affected. An
instance that never becomes ready:

- **No platform document is published.** Publish one with `cosec-migrate publish`.
- **A prewarmed document cannot be loaded or compiled.** The logs name it (`Loading snapshot document [<doc>]
  failed.`). Fix or publish the document, or remove it from the prewarm list.

The `cosecSnapshot` health indicator shows the state: `platform` (`installed`, `failed`, `absent` or `loading`) and
`prewarmed`. Add it to the readiness group to see it in the probe:
`management.endpoint.health.group.readiness.include=readinessState,cosecSnapshot`.

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

## Shadow window blocked by a candidate error

A shadow window is blocked as soon as one instance reports `CANDIDATE_ERROR`: it cannot compile the candidate, so it
cannot compare decisions, and the change is never published. The usual cause is a candidate that reads an `ext.*`
attribute whose provider runs on the control host but not on that instance. Attribute providers, and the types they
declare, must be the same on every data-plane instance and on the control host. Deploy the provider everywhere before such a
change is shadowed ([Testing changes](../policies/testing.md)).

## Startup failures

### Startup fails on an unknown property

```text
CoSec does not read <n> cosec.* properties, which would do nothing:
  cosec.authentication.enabled -> cosec.authentication.login.enabled
Remove or rename them; cosec.unknown-properties=warn only logs this.
```

A `cosec.*` key that no CoSec module reads: a typo, or a 7.x key, whose replacement follows `->`
([7.x keys](./upgrading.md#removed-and-renamed-properties)). Rename or remove it.
[`cosec.unknown-properties=warn`](./configuration.md#unknown-properties) only logs the list.

On Kubernetes, keys such as `cosec.gateway.service.host` and `cosec.gateway.port` come from service links: for every
Service in the namespace whose name starts with `cosec-`, Kubernetes sets variables such as
`COSEC_GATEWAY_SERVICE_HOST` and `COSEC_GATEWAY_PORT` in the pods it starts afterwards, and Spring reads them as
`cosec.*` keys. Set `enableServiceLinks: false` in the pod spec, as the manifests in `k8s/` do
([What the manifests set](./deployment.md#what-the-manifests-set)).

### Startup fails on the pipeline mode

- `cosec.pipeline.mode=gateway on a Spring Cloud Gateway host: ...`: a gateway always decides with policies. Remove
  the property from the gateway; set it on the services behind it.
- A binding failure for `cosec.pipeline.mode`: the value is neither `enforce` nor `gateway`.

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
| `cosec.audit.kafka.delivery=required needs cosec-kafka` | `cosec-kafka` with `spring-boot-starter-kafka`, or set [`delivery`](./configuration.md#audit) to `best-effort` |
| `cosec.audit.kafka.delivery=required, but no Kafka audit listener was created` | The module is there but the listener is not: a `KafkaOperations` bean (`spring-boot-starter-kafka` and its `spring.kafka.*` settings), `cosec.audit.enabled=true` and `cosec.audit.kafka.enabled=true` |
| `cosec.cosid.generator needs cosec-cosid` | `cosec-cosid`, with `cosid-spring-boot-starter` 5.x and its `cosid.*` generators, or remove the key to keep CoSec's own ids |
| `cosec-cocache needs a ReactiveStringRedisTemplate bean` | `spring-boot-data-redis` and a Redis connection (`spring.data.redis.*`): without it no snapshot is read and rate limits would count per instance |

The web checks run only while CoSec is on (`cosec.enabled`, default `true`); a gateway host is exempt from the WebFlux
check, as it needs `cosec-gateway` instead. An application that moved from 7.x's capabilities adds the modules
themselves ([Modules, artifacts and packages](./upgrading.md#modules-artifacts-and-packages)).

### Startup fails on the eviction check

```text
Redis may evict CoSec's policy snapshot: maxmemory-policy is {<node>=<policy>}, not noeviction, so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
Cannot tell whether Redis may evict CoSec's policy snapshot (CONFIG GET maxmemory-policy failed or is not allowed), so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
```

The first: a Redis node is not `noeviction`. Set `maxmemory-policy noeviction` on every node. The second: the instance
could not read the policy, usually because the Redis user may not run `CONFIG GET`, or a managed Redis hides it; it
also names a CoCache backend the check cannot read (a wrapper or a custom backend). Allow `CONFIG GET`, or, when you
have set `noeviction` yourself, set
[`cosec.authorization.snapshot.eviction-check=warn`](./configuration.md#snapshot) ([Redis requirements](./storage.md#redis-requirements)).

With `warn` the instance starts and logs the same text at `WARN`, starting with `!!!` and ending with
`Starting anyway (eviction-check=warn).` and what eviction would do. That line is expected with `warn`; if Redis really
evicts, an app or tenant document loaded after it was evicted is read as absent, and its DENY statements stop applying.

### Startup fails without a token store

```text
JWT is enabled, but no TokenStore records revoked tokens: add cosec-cocache or a TokenStore bean, or set cosec.jwt.enabled=false.
Tokens are verified, but no revocation reader is available: add cosec-cocache or a RevocationReader bean, or set cosec.jwt.enabled=false.
```

Token revocation is always on with JWT, and the starter never revokes into nothing. Add `cosec-cocache`, register a
`TokenStore` and a `RevocationReader` bean of your own, or turn JWT off in a service that neither issues nor verifies
tokens.

### Startup fails with a JWT secret error

`Property [cosec.jwt.secret] must not be blank when JWT is enabled.` Set
[`cosec.jwt.secret`](./configuration.md#jwt) to a long random value from a secret store, or set
`cosec.jwt.enabled=false`.

`Property [cosec.jwt.secret] has <n> bytes, and HMAC256 needs at least 32 (RFC 7518 §3.2).` The secret is shorter than
the algorithm's hash: 32 bytes (in UTF-8) for `hmac256`, 48 for `hmac384`, 64 for `hmac512`. Set a longer random
secret on the issuer and on every service that verifies its tokens; the tokens signed with the old one stop working.

### Startup fails on the route map

`The route map [<location>] (cosec.authorization.routes.location) does not exist; set the property empty to run without one.`
A WebFlux or WebMvc host loads `classpath:cosec-routes.json` by default, and fails when it is missing. Add the route
map (the `cosecServiceGenerator/routes` actuator endpoint generates one from the OpenAPI document), point
[`cosec.authorization.routes.location`](./configuration.md#authorization) at it, or set the property empty to authorize
every request in the `http` namespace. A route map that does not parse fails startup too, naming its location.

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
  `cosec.pipeline.mode=enforce`, where keys are read; a service in `gateway` mode reads none.
- `API key [<id>] needs the SHA-256 hex of the key as hash.`, `... needs a principal-id.`, `... needs a tenant-id.`,
  `Duplicate API key ids: [...].` Fix [`cosec.api-key.keys`](./configuration.md#api-keys).
- `API key [<id>] needs exactly one of roles (its cap; [] for no roles) and uncapped: true, not neither` (or `not
  both`): say what the key holds, its cap or `uncapped: true`; a key never becomes uncapped by omission.
- `cosec.api-key.enabled=[<value>] is neither true nor false.`: write `true` or `false`.
- A binding error naming `cosec.api-key.keys[n].policies` or `.attributes`: keys no longer carry them. Grant the
  principal its roles through the provider and narrow the key with `roles`
  ([API keys narrow, and memberships decide](./upgrading.md#api-keys-narrow-and-memberships-decide)).

### Other startup failures

| Message | Fix |
|---|---|
| `Decision listener <name> is registered twice.` | Give each `DecisionListener` its own `name`. |
| `The PolicySnapshotCache cache takes no loader: Redis is the store of record, and only the snapshot source reads it.` | A `CacheSpecCustomizer` sets a loader on every cache; exclude `PolicySnapshotCache`. |
| `lagWarn must not exceed lagEscalate.` and the other snapshot option checks | Fix [`cosec.authorization.snapshot.*`](./configuration.md#snapshot). |
| `Invalid change options.`, `Invalid shadow window options.` | Fix [`cosec.control.*`](./configuration.md#control-plane): `break-glass.max-duration` above `24h`, or `shadow.max-duration` below `shadow.min-duration`. |
| `cosec.control.revisions.reclaim-interval must be positive.`, `cosec.control.shadow.check-interval must be positive.` | Set a positive duration. |
| `cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created: it needs cosec-kafka, a KafkaOperations bean and cosec.control.audit.kafka.enabled=true.` | Add what it names, or set [`delivery`](./configuration.md#control-plane) to `best-effort`. |

## Control-plane errors

The request to the control API goes through the host's pipeline first, like any request, so the pipeline's own
`401`, `403` and `503` apply to it. Then the control plane authorizes each action again as that principal, in the app
[`cosec.control.app-id`](./configuration.md#control-plane). Its problem bodies use the same `type` prefix, and a
`400`, `409`, `410`, `412`, `422` or `428` explains the request in `detail`; a `401`, `403`, `404`, `500` or `503` never
does, so a refusal does not tell whether a document, a change or a DENY exists.

| Status | Cause | Fix |
|---|---|---|
| `401` | No principal the pipeline permitted stands behind the request: anonymous, or the route is not served behind the pipeline. | Send a credential. The documents must permit the HTTP request to `cosec.control.http.base-path` for this principal. |
| `403` | The operator may not take this action on this document, or the hard rules forbid it: the author approving their own change, someone else rebasing it. | Bind the operator a control-plane policy (`cosec:platform-admin`, `cosec:tenant-admin`, ...) for that document ([Testing changes](../policies/testing.md)). |
| `400` | The body is not a JSON object, a field is missing or unknown, a value has the wrong type, or (WebMvc) the body is larger than [`cosec.control.http.max-body-size`](./configuration.md#control-plane). | Fix the request; `detail` names the problem. On WebFlux a large body is bounded by `spring.codec.max-in-memory-size`. |
| `404` | No such route, document or change, or one the operator may not see. | |
| `409` | The base moved (`[<doc>] is at revision <n>, not <m>`), the change is in another state, another change holds the document's staging slot, or the shadow window is open or blocked. | Rebase, wait for the other change, or read the window's summary. |
| `410` | The revision asked for is not kept. | |
| `428` | Publishing needs an acknowledgement of the shadow window's counts. | Publish again with the `ack` of that window. |
| `503` | The authorization of the action could not be decided (no platform document, Redis unavailable), the store could not be read, or a `REQUIRED` change listener did not record a gated step. Break-glass without a `REQUIRED` change listener: `break-glass needs a REQUIRED change listener, and none is configured`. | Restore Redis or the listener's store ([Control-plane change log](./observability.md#control-plane-change-log)). |
| `500` | A defect: `Control API <method> <path> failed (request <requestId>).` at `ERROR`. | |

A change whose checks fail is not an error response: it stays `DRAFT` with its `findings`, each with a status (`422`
for a check, `412` for an `ifMatch` that does not hold).

## Form POST body is empty after CoSec on Spring MVC

A 7.x symptom: its WebMvc adapter read query parameters through `HttpServletRequest.getParameter`, which made the
servlet container parse an `application/x-www-form-urlencoded` body inside CoSec's filter, so the application found
the body empty and a form field could set `CoSec-App-Id` or `CoSec-Space-Id`. 8.0 reads only the URL query string and
never touches the body. Send the `CoSec-*` parameters as headers or query parameters ([Upgrading](./upgrading.md)).

## Audit events are missing

1. `cosec.audit.enabled` must be `true` (the default).
2. The audit log writes allows at `DEBUG`: set `me.ahoo.cosec.audit` to `DEBUG` to see them.
3. Events dropped by a full queue: `Dropping decision events: the queue of listener [<name>] is full.` and the
   `cosec.listener.dropped` counter. A listener that throws: `Decision listener [<name>] failed.`
4. Kafka: `Failed to publish audit event to Kafka topic [<topic>].`, or `The Kafka audit queue of [<topic>] is full.`
   ([Audit log and Kafka](./observability.md#audit-log-and-kafka)).
5. At shutdown, queued events go out for up to
   [`cosec.observability.listener.drain-timeout`](./configuration.md#decision-listeners) (5 seconds); the rest is dropped
   (`Decision listeners did not deliver every queued event within <timeout>; the rest is dropped.`).

**Next:** move an existing deployment to a newer version, in [Upgrading](./upgrading.md).

---
title: "Extending CoSec"
description: "Plug your own authenticators, membership sources, attribute providers, decision listeners, rate limiters, stores and route maps into the 8.0 pipeline."
---

# Extending CoSec

For developers who need behavior CoSec does not ship. This page lists every extension point of the 8.0 pipeline and
control plane, what each one must guarantee, and how the starter picks it up. The guarantees come from the
[architecture invariants](./overview.md#invariants): an extension that blocks a thread or hides a failure weakens every
decision.

## Extension points

With the starter you extend CoSec by declaring a Spring bean. Some extension points collect every bean of their type;
the others are single beans that replace the starter's default (`@ConditionalOnMissingBean`). Without Spring, pass your
implementation to the class that uses it, usually through `PipelineComponents`.

| Extension point | Module | Implement it to | The starter takes |
|---|---|---|---|
| `Authenticator` | `cosec-runtime` | Accept another kind of credential, or replace token and API key authentication. | one bean, replacing the built-in one |
| `TokenStore`, `RevocationReader` | `cosec-core` | Keep revoked tokens somewhere other than Redis. | one bean of each |
| `ApiKeyReader` | `cosec-core` | Look API keys up somewhere other than memory or Redis. | one bean |
| `MembershipProvider` | `cosec-core` | Supply the roles and attached policies of principals whose token does not carry them. | one bean |
| `AttributeProvider` | `cosec-runtime` | Give conditions external attributes (`ext.<provider>.<name>`). | every bean |
| `BlacklistSource` | `cosec-runtime` | Say which principals, addresses and devices are blocked. | one bean, wrapped in `BlacklistAttributeProvider` |
| `DecisionListener` | `cosec-runtime` | Observe every decision: audit, alerting, analytics. | every bean |
| `AuditEventSink` | `cosec-api` | Receive 7.x-style audit events. | every bean, as a `BEST_EFFORT` listener |
| `RateLimiter` | `cosec-runtime` | Count rate limit permits somewhere other than Redis or memory. | one bean, replacing the built-in one |
| `SnapshotDocumentStore` | `cosec-core` | Keep policy documents somewhere other than Redis. | one bean, without `cache-support` |
| `BridgeContextContributor` | `cosec-runtime` | Carry a thread-local context (logging, tracing) into the pipeline. | every bean |
| `ChangeListener` | `cosec-control` | Observe or gate control-plane changes. | every bean, with `control-support` |
| `ControlStore` | `cosec-control` | Keep the control plane's records somewhere other than Redis. | one bean, with `control-support` |
| Route maps | `cosec-core` | Give requests `service:verb` actions on typed resources. | `cosec.authorization.routes.location` |

Changing one of these interfaces (rather than implementing it) is a breaking change for every application that
implements it.

The adapters themselves call a `RequestGate` (`cosec-runtime`): the `Pipeline`, or, on a service behind a CoSec
gateway (`cosec.pipeline.mode=gateway`), `IdentityGate`. It is not an extension point: both build every outcome through
`StatusMapping`, and a gate of your own would be a second decision path.

## Rules every extension follows

The pipeline calls every SPI on the request path, often on a Netty event loop. Every SPI here follows the same
contract:

- **Never block the calling thread.** The SPIs are `suspend` functions (the snapshot store returns
  `CompletableFuture`s). A suspending client is fine. An implementation over a blocking client (JDBC, a blocking HTTP
  client) moves the call to its own bounded executor, for example with `withContext(myDispatcher)`. Do not use
  `Dispatchers.IO` inside CoSec modules (`RuntimeBoundaryTest` forbids it there); in your own code, prefer a bounded
  executor sized for the dependency.
- **Report failure by throwing.** Never answer "nothing" for a lookup that failed: an empty membership list, a missing
  attribute or "not revoked" can each turn a deny into a permit. The pipeline turns a thrown exception or a timeout into
  `Indeterminate`, which answers `503` unless a DENY matches.
- **Do not catch cancellation.** A client that goes away cancels the request's coroutine. Rethrow
  `CancellationException`, and do not use `runCatching` around a suspending call.
- **Keep it cheap.** Each call has a timeout (see [Configuration](../operations/configuration.md)); the whole request
  has `cosec.pipeline.timeout`. A local answer that does not suspend costs no thread switch and no timer.

## Authenticators

`Authenticator` turns a request's credentials (`Credentials.token`, `Credentials.apiKey`) into an `AuthnResult`:

| Result | When | What happens |
|---|---|---|
| `Authenticated(principal, method)` | The credential is valid. | The request is evaluated as that principal; `method` goes to audit. |
| `Anonymous` | No credential. | The request is evaluated as the anonymous principal. |
| `Rejected(reason)` | The credential is invalid, expired or revoked. | The request is evaluated as anonymous; a deny answers `401` with that reason. |
| `Unavailable(cause)` | A dependency failed. | `503`. A thrown exception is treated the same way. |

An `AuthenticatedPrincipal` names its id, tenant, membership source (the token's claims, or the
[membership provider](#memberships)), attributes and `credential`. Conditions read the attributes as
`subject.attributes.<name>`. `credential` (`Identity.Credential`: method `JWT` or `API_KEY`, the credential's id and
its expiry, never its value) is what a gateway forwards with the identity: on a gateway, a principal without one is
permitted but forwarded to no backend as authenticated, and one whose credential has no expiry fails the request with
`500`.

An authenticator sees only what the pipeline reads as credentials: the token (the `Authorization` header, else the query
parameter when `cosec.pipeline.allow-query-token` is on, else the cookie) and the API key header
(`cosec.pipeline.api-key-header`).

The starter's default is a `CompositeAuthenticator`: a request with an API key is judged by the API key authenticator
alone (when `cosec.api-key.enabled`), any other request by the JWT `TokenAuthenticator`. **Your `Authenticator` bean
replaces all of it.** To accept another token format and keep the built-in ones, build them yourself and delegate:

```kotlin
@Bean
fun cosecAuthenticator(algorithm: Algorithm, revocations: RevocationReader, keys: ApiKeyReader): Authenticator {
    val builtIn = CompositeAuthenticator(
        TokenAuthenticator(JwtTokenVerifier(algorithm), revocations),
        ApiKeyAuthenticator(keys),
    )
    val sessions = SessionTokenAuthenticator() // your own Authenticator for "Session ..." tokens
    return Authenticator { credentials ->
        if (credentials.token?.startsWith("Session ") == true) {
            sessions.authenticate(credentials)
        } else {
            builtIn.authenticate(credentials)
        }
    }
}
```

Give `TokenAuthenticator` the plain verifier (`JwtTokenVerifier`), never a `RevocableTokenVerifier`: revocation is
checked through the suspending `RevocationReader`. How tokens, claims and memberships work for users is in
[Authentication](../integration/authentication.md).

## Credentials and revocation

| Extension point | Contract |
|---|---|
| `TokenStore` | The write side of revocation, used by logout: `revoke(tokenId, ttl)`, `revokeSubject(tenant, principal, issuedBefore, ttl)`. An entry must outlive the refresh token it revokes. A store that cannot revoke every token of a principal throws from `revokeSubject`, so an emergency revocation never silently does nothing. |
| `RevocationReader` | The read side, called on every authenticated request: `isRevoked(tokenId)`, `subjectRevokedBefore(tenant, principal)`. Both suspend and must not block. |
| `ApiKeyReader` | `findByHash(hash): ApiKey?`, suspending. Return null for an unknown key; throw when the lookup fails (the request answers `503`). |

With `cache-support`, the starter wires `CoCacheTokenStore` and its reader. Without it, JWT fails startup unless you
register a `TokenStore` and a `RevocationReader` (or set `cosec.jwt.enabled=false`). Registering
`RevocationReader.NONE` is an explicit choice to revoke nothing.

The revocation read is one of the two documented exceptions to failing closed: a store whose degraded read cannot answer
reports "not revoked" itself. Anything a `RevocationReader` throws still answers `503`. The exceptions are listed in the
[security model](../guide/security-model.md).

## Memberships

A token carries a principal's roles and attached policies as claims, or, when they change often or do not fit, the
provider marker instead. For those principals the pipeline asks the `MembershipProvider`:

```kotlin
fun interface MembershipProvider {
    suspend fun memberships(tenant: TenantId, principal: PrincipalId): Memberships
}
```

- Answer `Memberships(Memberships.Source.PROVIDER, roles, attachments)`: roles by space (`SpaceId.DEFAULT` for the
  default space) and attached policy ids, in order. Any other source is rejected.
- Answer `Memberships(PROVIDER, emptyMap(), emptyList())` for an unknown principal. Throw when the lookup fails.
- A failure, a timeout (`cosec.authorization.membership.timeout`) or a missing provider makes the request `503`, never
  "no roles": no roles would also mean that no DENY bound to a role applies.

`InMemoryMembershipProvider` (`cosec-core`) is for tests. `CoCacheMembershipProvider` (`cosec-cocache`) caches what your
`MembershipLoader` answers in Redis, shared by every instance; call its `invalidate` when a principal's memberships
change. The starter registers neither: declare the one you want as a bean.

## External attributes

Conditions read the request, the principal and the scope directly. Anything else (a department from a directory, a risk
score, the client's region) comes from an `AttributeProvider`:

```kotlin
@Component
class DirectoryAttributeProvider(private val directory: DirectoryClient) : AttributeProvider {
    override val attributes = listOf(AttributeDeclaration("ext.directory.department", AttributeType.STRING))

    override val timeout: Duration = 200.milliseconds // kotlin.time; null uses cosec.authorization.attributes.timeout

    override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
        if (!request.subject.authenticated) {
            return emptyMap() // absent: a comparison with it is false
        }
        val department = directory.departmentOf(request.subject.tenantId, request.subject.id) // suspends, throws on failure
        return if (department == null) emptyMap() else mapOf("ext.directory.department" to department)
    }
}
```

```json
{ "eq": ["ext.directory.department", "finance"] }
```

- **Declare every attribute** as `ext.<provider>.<name>` with its type: `STRING`, `BOOL`, `STRING_SET` or `IP`. Each
  path is declared once across all providers. The declarations form the attribute catalog that documents compile
  against: a policy that reads an `ext.` path nobody declares does not compile. A platform document may also declare
  the catalog, so the control plane and every instance agree.
- **Register the same providers on every host.** The control plane checks a change against the platform's
  declarations and its own host's providers, and every instance compiles against its own. A provider missing on one
  instance makes a change it reads fail there: the instance reports `CANDIDATE_ERROR` and blocks the shadow window
  ([Deploying the gateway server](../operations/deployment.md#before-the-first-request)).
- **Values.** Return values by path, of the declared type. A path left out is *absent*. A value of the wrong type, a
  thrown exception or a timeout makes the attributes asked for *unavailable*, and each statement that reads them follows
  its `onMissing` setting ([Policy language](../policies/language.md)).
- **Stages.** A stage-B attribute (the default) is fetched only when the engine's plan says this request could read it,
  after the scope is resolved. A stage-A attribute is fetched on every request, before the scope, together with the
  memberships. Use stage A only for attributes that decide what is loaded, as the blacklist does.
- **What a provider sees.** `AttributeRequest` holds the subject, the resolved scope (null in stage A), the action,
  the resource, the client address, the request id and the device id, never another attribute's value.

**The blacklist** is a stage-A provider: `BlacklistAttributeProvider(source)` declares `ext.blacklist.principal`,
`ext.blacklist.ip` and `ext.blacklist.device`, and a request one of them blocks meets the built-in DENY
`cosec:blacklist`. Implement `BlacklistSource.contains(kind, value)` over your store (principals are
`<tenant>:<principal id>`), or use `InMemoryBlacklistSource`, and declare the source as a bean: the starter wraps it in
a `BlacklistAttributeProvider`. Without one, startup logs
`No BlacklistSource bean: requests are not checked against a blacklist (ext.blacklist.*).` at `INFO`. A source that
fails never means "not blocked": the request is `503` unless a DENY matches.

With `ip2region-support`, the starter registers `Ip2RegionAttributeProvider` (`ext.ip2region.country`, `.province`,
`.city`, `.isp`).

## Decision listeners

A `DecisionListener` observes every `DecisionEvent`: the outcome and status, the decision and the statement and binding
that decided it, the document revisions, the subject, scope, action and resource, the status (never the value) of each
external attribute, and the trace. It cannot change the decision.

```kotlin
@Component
class BreakGlassAlerts(private val pager: PagerClient) : DecisionListener {
    override val name = "break-glass-alerts" // unique among the listeners

    override suspend fun onDecision(event: DecisionEvent) {
        if (event.breakGlass) {
            pager.page("break-glass used by ${event.subject.tenant}/${event.subject.id}")
        }
    }
}
```

| `delivery` | Delivered | A failure |
|---|---|---|
| `BEST_EFFORT` (default) | Queued without suspending into the listener's own bounded queue, handled off the request path. A full queue drops the event. | Logged and counted; the response is unchanged. |
| `REQUIRED` | Before the response, together with the other REQUIRED listeners, within `cosec.audit.required.timeout`. | Turns a permit into `503`; any other outcome keeps its status. |

- A permit through a break-glass binding needs at least one REQUIRED listener, or it is a `503`.
- A REQUIRED listener should be idempotent by `event.id`: a write that timed out may still have succeeded.
- Every `DecisionListener` bean is registered, in `@Order` order, and so is every `AuditEventSink` bean that is not
  also a listener, as a `BEST_EFFORT` listener named after the bean. The starter adds the audit log
  (`LoggingAuditListener`, unless `cosec.audit.enabled=false`), and, with their features, `MetricsListener`,
  `TracingListener`, `KafkaAuditListener` and the shadow listener. Events, metrics and spans are described in
  [Observability](../operations/observability.md).
- `event.evaluationTrace` holds the engine's trace at `cosec.pipeline.trace-level`; it is null at `off`, the default.
- On shutdown, queued events are still delivered for up to `cosec.observability.listener.drain-timeout`.

## Rate limiters

`RateLimiter` counts the permits of a permit's rate limit obligations:

```kotlin
fun interface RateLimiter {
    suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult
}
```

Spend one permit of `limit` from `bucket` of rule `ruleId` and report what is left; `RateLimiter.window(limit)` gives
the sliding window both built-in limiters use, and its `resultOf(...)` builds the result. Throw when the backend
fails: the rule's `strictFailure` then decides between `503` and skipping the rule. With `cache-support` and a
`ReactiveStringRedisTemplate`, the starter registers `ReactiveRedisRateLimiter`, which counts across instances; otherwise
the per-instance `LocalRateLimiter`. Your bean replaces both. Rules, buckets and headers are in
[Rate limiting](../policies/rate-limiting.md).

## Snapshot document stores

`SnapshotDocumentStore` is where `SnapshotSource` reads the platform, app and tenant documents:

- `read(key)` may answer from a local copy (report its maximum age as `localCopyMaxAge`); `readIfChanged(key, known)`
  must read the store of record and answer `Unchanged` when the digest matches; `subscribe(listener)` reports changes
  after they are readable.
- Every method returns without blocking and never throws: a failure completes the future exceptionally. A stored value
  that cannot be decoded carries a `SnapshotFormatException` in its cause chain.
- Never delete a document. A key that once had a document and then reads as absent fails closed.

With `cache-support`, the starter uses `RedisSnapshotDocumentStore`, and the control plane writes through the same Redis.
Without it, the starter registers an `InMemorySnapshotDocumentStore`; put documents into it with `put(key, document)`, or
declare your own `SnapshotDocumentStore` bean instead. Requests fail closed until a platform document is there.

## Bridge context

The adapters start each request's coroutine with a context captured on the thread that received it. A
`BridgeContextContributor` adds to that context, so a thread-local your code relies on is restored whenever the pipeline
resumes on another thread:

```kotlin
fun interface BridgeContextContributor {
    fun capture(): CoroutineContext
}
```

The starter registers `MdcContextContributor` (the SLF4J MDC) and, with `opentelemetry-support`, the OpenTelemetry
context. Every `BridgeContextContributor` bean is used, in `@Order` order. `capture` runs once per request: keep it cheap.

## Change listeners

With `control-support`, a `ChangeListener` receives a `ChangeEvent` for every step of a change: proposed, checked,
shadowed, approved, publishing, published, break-glass requested or approved, and so on. An event never carries document
content.

- A `REQUIRED` listener gates publishing and every step of creating break-glass: the step happens only after every
  REQUIRED listener recorded it within `cosec.control.audit.required-timeout` (5 s), otherwise it fails with `503` and
  writes nothing. Creating break-glass needs at least one REQUIRED listener.
- Other events, and every event to a `BEST_EFFORT` listener, are delivered off the caller; a failure is logged.
- Listeners add up: every `ChangeListener` bean is registered next to the starter's `LoggingChangeListener`, which
  backs off only for a `LoggingChangeListener` bean of yours.

With `kafka-support` and a `KafkaOperations` bean, the starter registers `KafkaChangeListener` under
`cosec.control.audit.kafka.*` ([Observability](../operations/observability.md#control-plane-change-log)).

## Control store

With `control-support`, the control plane keeps revisions, change requests, staging slots, candidates and leases in a
`ControlStore`; the starter's is `CoCacheControlStore` on Redis. Besides reading and writing those records by key, a
store answers what the maintenance passes visit:

- `documents()`: every document it has indexed revisions for. It may name more than exist, never fewer.
- `openChanges()`: the change requests that may still be open. It may name more, never fewer; callers read each
  change and go by its state.
- `acquireLease(name, holder, duration)` and `releaseLease(name, holder)`: one instance at a time per task. A release
  removes the lease only while `holder` still has it.

Like the data plane's stores, a read that fails throws: it never answers "absent".

## Route maps

By default a request's action is `http:<METHOD>` and its resource `http:path:<path>`. A route map gives a service's
routes their own actions and typed resources (`order:read` on `order/{orderId}`), so policies are written in the
service's terms. Mapped requests get the route's action; every other request keeps the `http` namespace.

```json
{
  "service": "order",
  "routes": [
    { "method": "GET", "path": "/orders/{orderId}", "action": "order:read", "resource": "order/{orderId}" },
    { "method": "POST", "path": "/orders/{orderId}/cancel", "action": "order:cancel", "resource": "order/{orderId}" }
  ]
}
```

- The starter loads the route maps listed in `cosec.authorization.routes.location` (default
  `classpath:cosec-routes.json`, comma-separated) at startup. A missing or invalid map, or two routes one request could
  match, fails startup. That includes the default location: a WebFlux or WebMvc service without a route map sets the
  property empty. A gateway host loads none.
- With `openapi-support` and Spring Boot Actuator, the `cosecServiceGenerator` endpoint generates both documents from
  the service's OpenAPI description: `routes` is the route map to ship as `cosec-routes.json`, `app` is the app document
  (catalog and permission policies) to publish through the control plane.

The file is read strictly: an unknown or missing field fails it
([`RouteMapCodec.kt`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/route/RouteMapCodec.kt)).
App documents and catalogs are in [Role permissions](../policies/role-permissions.md).

**Next:** how to propose and land a change, in [`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/CONTRIBUTING.md), and the build rules and invariants for changing CoSec, in [`AGENTS.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/AGENTS.md).

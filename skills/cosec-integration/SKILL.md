---
name: cosec-integration
description: Integrate CoSec 8.0 into a Spring Boot service - choose the CoSec modules to add next to the starter (WebFlux, WebMVC, Gateway, Redis cache, control plane, ...), configure JWT and revocation, Redis with noeviction, route maps, the first platform document, checks in code with AuthorizationChecker, and hosting the control plane. Do not use for policy-only authoring or diagnosis.
---

# CoSec Spring Boot Integration

Make the smallest integration that fits the target application. Preserve its build tool, dependency-management style,
HTTP stack, configuration format, and existing security boundaries. Guide: https://cosec.ahoo.me/guide/quick-start.

## Inspect first

Determine:

- Spring WebFlux, Spring MVC, or Spring Cloud Gateway; add exactly the matching transport.
- For a service behind a CoSec gateway: whether it decides for itself (default) or trusts the gateway's decision
  (gateway mode, below), and whether only the gateway can reach it.
- The CoSec version already managed by a BOM, version catalog, parent, or lockfile. Never copy a version from this skill.
- Whether the service has Redis (needed for shared policy storage, token revocation, cluster-wide rate limits and the
  control plane), and whether this service should also host the control plane.
- Whether OAuth login, IP regions, tracing, metrics, OpenAPI or Kafka audit are actually required.

The starter, `me.ahoo.cosec:cosec-spring-boot`, brings `cosec-runtime`, `cosec-login` and `cosec-jwt`, and wires every
other CoSec module it finds on the classpath. Add each integration as a dependency of its own, with what it needs:

| Need | Module | Also add |
|---|---|---|
| Spring MVC | `cosec-webmvc` | |
| Spring WebFlux | `cosec-webflux` | |
| Spring Cloud Gateway | `cosec-gateway` | |
| Redis: policy snapshot, revocation, API keys, cluster-wide rate limits, shadow | `cosec-cocache` | `org.springframework.boot:spring-boot-data-redis`, `me.ahoo.cocache:cocache-spring-boot-starter` |
| Control plane (change, simulate, explain, publish) | `cosec-control` | `cosec-cocache` |
| Social OAuth login | `cosec-social` | |
| IP region attributes (`ext.ip2region.*`) | `cosec-ip2region` | |
| OpenTelemetry spans | `cosec-opentelemetry` | |
| Micrometer metrics | `cosec-micrometer` | `io.micrometer:micrometer-core` |
| OpenAPI route map and app document generation | `cosec-openapi` | `org.springframework.boot:spring-boot-starter-actuator`, `org.springdoc:springdoc-openapi-starter-common` |
| Kafka audit | `cosec-kafka` | `org.springframework.boot:spring-boot-starter-kafka` |

```kotlin
implementation(platform(libs.cosec.bom))
implementation("me.ahoo.cosec:cosec-spring-boot")
implementation("me.ahoo.cosec:cosec-webflux")
implementation("me.ahoo.cosec:cosec-cocache")
implementation("org.springframework.boot:spring-boot-data-redis")
implementation("me.ahoo.cocache:cocache-spring-boot-starter")
```

Maven takes the same artifacts. The "Also add" column mirrors the `compileOnly` lines of
`host/spring-boot/build.gradle.kts`; with Maven, import `me.ahoo.cosec:cosec-dependencies` or pin their versions.
There are no starter capabilities (`requireCapability(...-support)`) in 8.0: replace each with its module.
Startup fails with `... needs <artifact> (me.ahoo.cosec:<artifact>) on the classpath ... (STA-1)` when the web stack's
adapter is missing (MVC, WebFlux, or Gateway even with `cosec.enabled=false`), when `cosec.api-key.store=redis` lacks
`cosec-cocache`, or when `cosec.audit.kafka.delivery=required` lacks `cosec-kafka`: add the artifact it names.

## Minimal configuration

```yaml
cosec:
  jwt:
    algorithm: hmac256          # hmac256 | hmac384 | hmac512
    secret: ${COSEC_JWT_SECRET} # blank fails startup while JWT is enabled
  authorization:
    scope:
      app-id: order-service     # the app this host serves; omit when requests declare it (CoSec-App-Id)
    remote-ip:
      max-trusted-index: 1      # trusted X-Forwarded-For hops; 0 ignores the header
    routes:
      location: classpath:cosec-routes.json   # the default; set it empty to run without a route map
```

Startup fails, on purpose, when:

- **JWT is on without revocation storage.** Revocation is always on. With `cosec-cocache` the Redis token store
  supplies it; without it, register a `TokenStore` and a `RevocationReader` bean yourself, or set
  `cosec.jwt.enabled=false`.
- **The route map location does not exist.** The default `classpath:cosec-routes.json` must exist on WebFlux and
  WebMvc hosts unless `cosec.authorization.routes.location` is set empty. A gateway host loads no route map.
- **Redis may evict the snapshot.** With `cosec-cocache`, every Redis node must have `maxmemory-policy noeviction`. A
  different policy, or one that cannot be read (`CONFIG GET` denied on a managed Redis, a wrapped CoCache backend),
  fails startup. Only when the operator has set `noeviction` and CoCache cannot read it, set
  `cosec.authorization.snapshot.eviction-check=warn` (it starts and logs a `!!!` warning).
- **API keys with `store=redis` and no `ReactiveStringRedisTemplate`.**

Every `cosec.*` property: https://cosec.ahoo.me/operations/configuration. In a CoSec checkout the source of truth is
`host/spring-boot/src/main/kotlin/.../pipeline/PipelineProperties.kt`, `control/ControlProperties.kt` and the
auto-configurations listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Route maps

On WebFlux and WebMvc hosts, `cosec-routes.json` gives requests their `service:verb` action and typed resource:

```json
{ "service": "order",
  "routes": [
    { "method": "GET",  "path": "/orders/{orderId}",        "action": "order:read",   "resource": "order/{orderId}" },
    { "method": "POST", "path": "/orders/{orderId}/cancel", "action": "order:cancel", "resource": "order/{orderId}" } ] }
```

It loads all or nothing: overlapping routes for one method, a malformed route, or a resource variable the path does not
capture fails startup. Requests no route matches stay `http:<METHOD>` on `http:path:<path>`. With `cosec-openapi`,
`GET /actuator/cosecServiceGenerator/routes` generates the route map and `/app` the app document. Policies on `http:*`
never cover mapped requests: tell the policy author which namespace each endpoint is in.

## Publishing the first documents

The service decides on a snapshot of documents (platform, app, tenant), read from Redis with `cosec-cocache` or from an
in-memory store without it. **Until a platform document exists, every request is 503.**

- With Redis: write the documents into a directory (`platform.json`, `apps/<appId>.json`, `tenants/<tenantId>.json`)
  and run `cosec-migrate publish --to-redis redis://... [--key-prefix cosec] --in <dir>`. It publishes first
  revisions only, never overwrites, and reads a `report.json` with each file's SHA-256 (`convert` writes it; for
  hand-written files write `{"accepted": true, "outputSha256": {"platform.json": "<sha256>"}}`). Migrating 7.x data:
  `cosec-migrate convert`, then `publish`; see https://cosec.ahoo.me/operations/upgrading.
- Later changes go through the control plane (below). `cosec-migrate bootstrap` writes only the platform tenant's
  document; it does not create the platform document.
- While no platform document is installed, the instance also reports itself not ready (Spring readiness
  `REFUSING_TRAFFIC`; liveness unaffected). With actuator, add the `cosecSnapshot` indicator to the readiness group
  (`management.endpoint.health.group.readiness.include=readinessState,cosecSnapshot`); it is `DOWN` until the platform
  document and every `prewarm-apps`/`prewarm-tenants` document are installed, so `/actuator/health` is `DOWN` too.
- Without Redis: the `SnapshotDocumentStore` bean is an `InMemorySnapshotDocumentStore`; put documents into it
  (`put(SnapshotKey.Platform, SnapshotDocument(2, revision, publishedAt, bytes))`), for example from files at startup.
  Each instance holds its own copy.

Use `$cosec-policy-author` to write the documents. Storage details: https://cosec.ahoo.me/operations/storage.

## Services behind a CoSec gateway

The gateway strips any client `CoSec-Identity` and, on a permit for an authenticated principal, forwards the identity
it verified (tenant, principal, app, space, the roles there, credential id and expiry, and the claims named in
`cosec.gateway.identity.claims`). It no longer forwards `Authorization` unless
`cosec.gateway.identity.forward-authorization=true`. Route metadata `cosec-identity: false` opts a route out. A
credential without expiry (an API key without `expires-at`) makes the gateway answer 500.

A service behind it either keeps deciding itself (default `cosec.pipeline.mode=enforce`: same documents, keys and Redis
as the gateway, and `forward-authorization=true` so it gets the token), or trusts the gateway:

```yaml
cosec:
  pipeline:
    mode: gateway   # read the caller from CoSec-Identity; no snapshot, engine, route map, listeners or Redis
  jwt:
    enabled: false  # it verifies no tokens, so it needs no secret
```

- Add only the web module (`cosec-webflux` or `cosec-webmvc`); no `cosec-cocache`. No `AuthorizationChecker`
  is wired in this mode.
- **Only the gateway may reach it** (NetworkPolicy, private network or mTLS): it believes any `CoSec-Identity`, and
  logs a WARN at startup saying so. Never expose such a service directly.
- Answers: no header is anonymous; unreadable, repeated or oversize (`cosec.pipeline.gateway.max-size`, 8KB) is 400;
  older than `cosec.pipeline.gateway.max-age` (60s) plus `cosec.authentication.revocation.skew`, or past the
  credential's expiry, is 401 `token-expired`; unknown version, no expiry or an identity from the future is 401
  `token-invalid`; a scope that does not resolve here (for example a different `scope.app-id`) is 400.
- Startup fails for an unknown `cosec.pipeline.mode`, and for `mode=gateway` on a Spring Cloud Gateway host.

Details: https://cosec.ahoo.me/integration/web#services-behind-the-gateway and
https://cosec.ahoo.me/guide/security-model#trust-boundaries.

## Tokens, memberships and API keys

- Tokens are read from the `Authorization` header, then the `authorization` cookie; the query parameter only with
  `cosec.pipeline.allow-query-token=true`. Issue them with the `TokenConverter` bean.
- A token's `roles` claim (`role` in the default space, `role@space` elsewhere) and `policies` claim (attached policy
  ids, which must be `attachable`) are the principal's memberships. A token with the claim `cosec.membership:
  "provider"` and no roles or policies takes them from a `MembershipProvider` bean instead; a `JwtTokenConverter` bean
  of your own with `membershipClaimLimit` set issues such tokens when the claims would exceed that many bytes (the
  starter's converter never does). Register the provider before issuing them, or those requests are 503.
- Logout: `TokenRevoker.revoke(accessToken)`. Emergency: `TokenRevoker.revokeSubject(tenant, principal)` revokes every
  token of a principal issued until now (needs the Redis token store).
- API keys: `cosec.api-key.enabled=true`, keys in `cosec.api-key.keys[n]` (`id`, `hash` = SHA-256 hex of the key,
  `principal-id`, `tenant-id`, `roles`, `policies`, `attributes`, `expires-at`) or `cosec.api-key.store=redis`. They
  arrive in `X-API-Key` (`cosec.pipeline.api-key-header`).
- Clients send `CoSec-App-Id` (unless the host sets `scope.app-id`) and `CoSec-Space-Id` for a non-default space; a
  space needs a role there, or the request is denied (403 `scope-rejected`; 401 for an anonymous caller). Details:
  https://cosec.ahoo.me/integration/authentication.

## Adapters and checks in code

The starter registers one filter: `PipelineServletFilter` (MVC), `PipelineWebFilter` (WebFlux) or
`PipelineGatewayFilter` (Gateway). Adapters only translate; status codes and problem+json bodies are decided once, in
the pipeline: 401/403 deny, 429 rate limited, 503 undecidable, 400 ambiguous request. A permitted request carries a
`SecurityContext`: `ServerWebExchange.getSecurityContext()` / `HttpServletRequest.getSecurityContext()` (extension
functions in `ServerWebExchanges` / `ServletRequests`). Web details: https://cosec.ahoo.me/integration/web.

To decide in code (UI buttons, message consumers, object-level checks), inject `AuthorizationChecker`:

```kotlin
checker.check(securityContext, "order:ship", "order/42")      // suspend
checker.checkMono(securityContext, "order:ship", "order/42")  // cosec-webflux
checker.checkBlocking(securityContext, "order:ship", "order/42") // cosec-webmvc
```

The resource is `type/id`, or a path (`/orders/42`) for an `http:` action. It uses the principal and scope of the
permitted request, runs no rate limits and notifies no listener, and is `false` on any deny, error or outage. Keep
enforcing on the real request. See https://cosec.ahoo.me/integration/checks.

## Hosting the control plane

With `cosec-control` (and `cosec-cocache`), the host serves the control API under
`{cosec.control.http.base-path}/v1` (default `/cosec/control/v1`): documents, changes, shadow windows, approval,
break-glass, `simulate`, `explain`, `analyze`. Each call first passes the host pipeline (so the host's documents must
permit the authenticated caller on that path), then is authorized again in app `cosec.control.app-id`
(`cosec-control`) against the built-in `cosec:platform-admin`, `cosec:tenant-admin` and `cosec:tenant-viewer`
policies. Bind the first administrator with `cosec-migrate bootstrap --to-redis ... --platform-admin <principal>`
(a `(platform)`-tenant principal), or with a principal binding of the platform document to `cosec:platform-admin`
in app `cosec-control`. On WebFlux, raise `spring.codec.max-in-memory-size` for large documents; on WebMvc the limit is
`cosec.control.http.max-body-size` (16MB). Workflow: https://cosec.ahoo.me/policies/testing.

## Optional behavior

- Audit: decisions are logged under `me.ahoo.cosec.audit` (denies WARN, allows DEBUG) unless
  `cosec.audit.enabled=false`; `cosec-kafka` adds a Kafka listener (`cosec.audit.kafka.*`). Existing
  `AuditEventSink` beans are picked up as BEST_EFFORT listeners. Queued events drain on shutdown for up to
  `cosec.observability.listener.drain-timeout` (5s). Metrics and tracing
  listeners come with their features. https://cosec.ahoo.me/operations/observability.
- Rate limits are rules in the documents. The limiter is Redis when a `ReactiveStringRedisTemplate` is present,
  otherwise per JVM.
- Shadow windows: with `cosec-cocache`, the instance replays a sample (`cosec.authorization.shadow.sample-rate`,
  default 0.1) of requests on staged candidates and reports differences to the control plane.
- Blacklist: declare a `BlacklistSource` bean and the starter registers the blacklist provider; without one, startup
  logs an INFO line and nothing is blocked.
- Extension beans (`AttributeProvider`, `MembershipProvider`, `DecisionListener`, `RateLimiter`, `Authenticator`): use
  `$cosec-custom-matcher`. Register the same attribute providers on every instance and on the control-plane host.
- Policy tests: `cosec-testkit` (test scope) runs scenarios against your documents; use `$cosec-policy-author`.

## Verification

Start the application or run its context test, then verify:

- startup succeeds (no eviction-check, route map or revocation failure);
- before the platform document is published, requests answer 503 and readiness is `REFUSING_TRAFFIC`; after, readiness
  turns `ACCEPTING_TRAFFIC` and an allowed anonymous request succeeds;
- in gateway mode: a request without `CoSec-Identity` is anonymous, and the service is unreachable except from the
  gateway;
- an unmatched anonymous request returns 401 `application/problem+json`, an authenticated denied one 403;
- a request over a rate limit rule returns 429 with `Retry-After` and `RateLimit-*` headers;
- a revoked token returns 401 `token-revoked`;
- with Redis, a change published on one instance reaches the others (they revalidate every
  `cosec.authorization.snapshot.revalidate-interval`, default 10s).

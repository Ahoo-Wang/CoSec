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
  control plane), and whether this service should also host the control plane or the control plane runs as the
  `cosec-control` image (below).
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
| Ids from CosId 5 | `cosec-cosid` | `me.ahoo.cosid:cosid-spring-boot-starter` 5.x |
| OpenTelemetry spans | `cosec-opentelemetry` | |
| Micrometer metrics | `cosec-micrometer` | |
| OpenAPI route map and app document generation | `cosec-openapi` | `org.springframework.boot:spring-boot-starter-actuator`, `org.springdoc:springdoc-openapi-starter-webflux-api` or `-webmvc-api` |
| Kafka audit | `cosec-kafka` | `org.springframework.boot:spring-boot-starter-kafka` |

```kotlin
implementation(platform(libs.cosec.bom))
implementation("me.ahoo.cosec:cosec-spring-boot")
implementation("me.ahoo.cosec:cosec-webflux")
implementation("me.ahoo.cosec:cosec-cocache")
implementation("org.springframework.boot:spring-boot-data-redis")
implementation("me.ahoo.cocache:cocache-spring-boot-starter")
```

Maven takes the same artifacts. The "Also add" column is what each module needs next to it; give each its version yourself, with Gradle or Maven: no CoSec module imports a platform
([what CoSec needs from your build](https://cosec.ahoo.me/guide/introduction#what-cosec-needs-from-your-build): Spring Boot 4.1, CoCache 8 for `cosec-cocache`, JDK 21).
There are no starter capabilities (`requireCapability(...-support)`) in 8.0: replace each with its module.
Startup fails with `... needs <artifact> (me.ahoo.cosec:<artifact>) on the classpath ... (STA-1)` when the web stack's
adapter is missing (MVC, WebFlux, or Gateway even with `cosec.enabled=false`), when `cosec.api-key.store=redis` lacks
`cosec-cocache`, or when `cosec.audit.kafka.delivery=required` lacks `cosec-kafka`: add the artifact it names.

## Minimal configuration

```yaml
cosec:
  jwt:
    secret: ${COSEC_JWT_SECRET} # HMAC key; blank fails startup while JWT is enabled
```

Everything else has a default. Add a setting only for the need it serves, and know its consequence:

| Add | When | Consequence |
|---|---|---|
| `cosec.authorization.scope.app-id: order-service` | the host serves one app | Only credentials declaring that app are served: a token issued with `TokenSubject(app = null)`, as the Quick Start's demo issues, is `403` `scope-rejected` on every request |
| `cosec.authorization.routes.location: classpath:cosec-routes.json` | actions come from a route map | The file must exist and hold routes, or startup fails; unset, the host loads none and a request is `http:<METHOD>` |
| `cosec.authorization.remote-ip.max-trusted-index: 0` | clients reach the host directly | The default `1` trusts one `X-Forwarded-For` hop, which a directly exposed host's clients can forge; `0` ignores the header |
| `cosec.authentication.revocation.fail-closed: true` | a Redis outage must not accept tokens whose revocation cannot be read | Every authenticated request is `503` while revocations cannot be read |

Startup fails, on purpose, when:

- **JWT is on without revocation storage.** Revocation is always on. With `cosec-cocache` the Redis token store
  supplies it; without it, register all three beans yourself (`TokenStore`, `RevocationReader`, `TokenFamilyStore`), or
  set `cosec.jwt.enabled=false` on a service that issues no tokens and reads none. **A service that only verifies tokens
  another service issues must read the issuer's revocations**: `cosec-cocache` on the issuer's Redis with the issuer's
  `cosec.key-prefix`. `RevocationReader.NONE` is only for tokens that nobody, anywhere, revokes: with it the service
  accepts every token the issuer revoked until it expires
  (https://cosec.ahoo.me/scenarios/resource-server).
- **A route map is left out or empty.** `classpath:cosec-routes.json` exists while
  `cosec.authorization.routes.location` is unset (name it, or set the property empty to run without one); a location
  that does not exist; a map with no routes. A gateway host loads no route map.
- **Redis may evict the snapshot.** With `cosec-cocache`, every Redis node must have `maxmemory-policy noeviction`. A
  different policy, or one that cannot be read (`INFO` denied to the Redis user, a wrapped CoCache backend),
  fails startup. Only when the operator has set `noeviction` and CoCache cannot read it, set
  `cosec.authorization.snapshot.eviction-check=warn` (it starts and logs a `!!!` warning) and
  `cocache.redis.assume-maxmemory-policy=noeviction`: CoCache 8 checks its `Store`s too, and refuses `allkeys-*` and an
  unreadable policy whatever CoSec's setting is. The policy is read again while the service runs: the
  `cosecSnapshotEviction` health indicator is `DOWN` while Redis may evict (with `warn`, a policy it cannot read is
  `UP`, `eviction: unknown`). It is an alert: keep it out of the readiness group.
- **A `cocache.caches.[<CoSec cache>].*` entry or a `CacheSpecCustomizer` sets a field CoSec locks** (the snapshot
  store's `ttl`, `unknown-read`, `local-max-age` or `key-prefix`, say): startup fails. Remove it, or exclude CoSec's
  caches from the customizer. An entry for a CoSec cache a service does not create (a control-plane cache, in a
  configuration every service shares) is fine.
- **Redis read from replicas.** The Redis templates CoSec injects (the only or the `@Primary`
  `StringRedisTemplate` / `ReactiveStringRedisTemplate`) must read the primary: `spring.data.redis.lettuce.read-from`
  (or a client customizer's `ReadFrom`) other than `upstream` on their factory fails startup.
- **API keys with `store=redis` and no `ReactiveStringRedisTemplate`.**

Every `cosec.*` property: https://cosec.ahoo.me/operations/configuration. In a CoSec checkout the source of truth is
`host/spring-boot/src/main/kotlin/.../pipeline/PipelineProperties.kt`, `control/ControlProperties.kt` and the
auto-configurations listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Route maps

On WebFlux and WebMvc hosts, a route map named by `cosec.authorization.routes.location` gives requests their
`service:verb` action and typed resource:

```json
{ "service": "order",
  "routes": [
    { "method": "GET",  "path": "/orders/{orderId}",        "action": "order:read",   "resource": "order/{orderId}" },
    { "method": "POST", "path": "/orders/{orderId}/cancel", "action": "order:cancel", "resource": "order/{orderId}" } ] }
```

It loads all or nothing: overlapping routes for one method, a malformed route, a map with no routes, or a resource
variable the path does not capture fails startup. Requests no route matches stay `http:<METHOD>` on `http:path:<path>`. With `cosec-openapi` and springdoc,
`GET /actuator/cosecServiceGenerator/routes` generates the route map and `/app` the app document (short-form
permissions: catalog entries with `action`). A request is mapped or not, never both: `http:*` statements, DENYs and
rate limit rules never cover a mapped request, and `service:verb` ones never cover an unmapped one. Tell the policy
author which namespace each endpoint is in. A service that needs no business names, such as a small one or one
behind a gateway (which loads no route map), leaves the property unset and writes `http` statements; that is "simple mode"
(https://cosec.ahoo.me/scenarios/simple-mode). More: https://cosec.ahoo.me/scenarios/route-maps.

## Publishing the first documents

The service decides on a snapshot of documents (platform, app, tenant), read from Redis with `cosec-cocache` or from an
in-memory store without it. **Until a platform document exists, every request is 503.**

- With Redis: write the platform document and run
  `cosec-migrate publish --to-redis redis://... [--key-prefix cosec] --file platform.json --document platform`
  (`--document app:<id>` or `tenant:<id>` for others, after the platform's). It publishes first revisions only, never
  overwrites, and makes the checks a converted directory gets (`--in <dir>`, which reads `convert`'s `report.json`
  with each file's SHA-256). Migrating 7.x data:
  `cosec-migrate convert`, then `publish`; see https://cosec.ahoo.me/operations/upgrading.
  The tool is `cosec-migrate-<version>.zip` on each GitHub release (Java 21+; as a Kubernetes Job in the same page). Seed
  hand-written app and tenant documents after the platform document with `bootstrap` and the control plane's
  `POST .../documents/app:<id>:apply` (https://cosec.ahoo.me/operations/storage#seeding-documents-you-wrote-by-hand).
- Later changes go through the control plane (below). `cosec-migrate bootstrap` writes only the platform tenant's
  document; it does not create the platform document. **The first platform document must let authenticated callers
  reach `/cosec/control/v1/**`** (`http:GET`, `http:POST`, `http:DELETE` on `http:path:/cosec/control/v1/**`, condition
  `subject.authenticated`): the control plane authorizes again per step, but a document that does not open the path can
  no longer be changed, and `publish` never overwrites. https://cosec.ahoo.me/guide/quick-start has a document that does.
- While no platform document is installed, the instance also reports itself not ready (Spring readiness
  `REFUSING_TRAFFIC`; liveness unaffected). With actuator, add the `cosecSnapshot` indicator to the readiness group
  (`management.endpoint.health.group.readiness.include=readinessState,cosecSnapshot`); it is `DOWN` until the platform
  document and every `prewarm-apps`/`prewarm-tenants` document are installed, so `/actuator/health` is `DOWN` too.
- Without Redis: the `SnapshotDocumentStore` bean is an `InMemorySnapshotDocumentStore`; put documents into it
  (`put(SnapshotKey.Platform, SnapshotDocuments.read(bytes))`, which reads the header from the bytes), for example from files at startup.
  Each instance holds its own copy.

Use `$cosec-policy-author` to write the documents. Storage details: https://cosec.ahoo.me/operations/storage.

## Services behind a CoSec gateway

The gateway strips any client `CoSec-Identity` and, on a permit for an authenticated principal, forwards the identity it
verified (tenant, principal, app, space, the roles there, credential id and expiry, and the claims named in
`cosec.gateway.identity.claims`) to a route that receives it: a route to a registered service (`uri: lb://...`), or one
whose metadata says `cosec-identity: true`. Any other route, and one marked `cosec-identity: false`, gets none: mark
your own `http(s)://` services `true`, never a third party. It forwards no credentials (`Authorization`, the API key
header, the `authorization` query parameter, the `authorization` cookie) unless
`cosec.gateway.identity.forward-authorization=true`, and then only to a route that would receive the identity. A
credential without expiry (an API key without `expires-at`) makes the gateway answer 500.

On a gateway that fronts several apps, give each route its app with the metadata `cosec-app-id` (it takes precedence
over `cosec.authorization.scope.app-id`), or the client chooses the app, and with it whose DENYs apply:

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: billing
              uri: lb://billing           # lb://: receives the identity
              predicates: [Path=/billing/**]
              metadata:
                cosec-app-id: billing     # the app of this route's requests
            - id: orders
              uri: https://orders.internal
              predicates: [Path=/orders/**]
              metadata:
                cosec-app-id: order
                cosec-identity: true      # an http(s) route receives the identity only when marked
management:
  server:
    port: 8081   # actuator off the application port: CoSec does not authorize it (STA-2)
```

A gateway host does not start while an actuator endpoint other than `health` is exposed on its application port:
serve actuator on `management.server.port`, expose only `health`, or list the protected ones in
`cosec.gateway.unprotected-endpoints` (never `gateway`).

A service behind it either keeps deciding itself (default `cosec.pipeline.mode=enforce`: same documents, keys and Redis
as the gateway, and `forward-authorization=true` so it gets the token), or trusts the gateway:

```yaml
cosec:
  pipeline:
    mode: gateway   # read the caller from CoSec-Identity; no snapshot, engine, route map, listeners or Redis
    identity:
      only-gateway-can-reach: true   # the operator's statement; startup fails without it (STA-7)
  jwt:
    enabled: false  # it verifies no tokens, so it needs no secret
```

- Add only the web module (`cosec-webflux` or `cosec-webmvc`); no `cosec-cocache`. No `AuthorizationChecker`
  is wired in this mode.
- **Only the gateway may reach it** (NetworkPolicy, private network or mTLS): it believes any `CoSec-Identity`, and does
  not start until `cosec.pipeline.identity.only-gateway-can-reach=true` states that it is so (`STA-7`; write it only when it
  is true). It also fails to start with a hidden-method filter (`spring.mvc.hiddenmethod.filter.enabled`,
  `spring.webflux.hiddenmethod.filter.enabled`, or a `HiddenHttpMethodFilter` bean, `STA-6`): the gateway removes the
  method-override headers and decided the request line's method. Never expose such a service directly.
- Answers: no header is anonymous; unreadable, repeated or oversize (`cosec.pipeline.identity.max-size`, 8KB) is 400;
  older than `cosec.pipeline.identity.max-age` (60s) plus `cosec.authentication.revocation.skew`, or past the
  credential's expiry, is 401 `token-expired`; unknown version, no expiry or an identity from the future is 401
  `token-invalid`; a scope that does not resolve here (for example a different `scope.app-id`) is 400.
- Startup fails for an unknown `cosec.pipeline.mode`, and for `mode=gateway` on a Spring Cloud Gateway host.
- Tests of such a service, without a gateway: `IdentityHeader` (`cosec-testkit`, experimental) writes the header a
  gateway sends: `forSubject(subject, tenant, app, space, roles)`, `of(identity)`, or `withToken(token, verifier,
  revocations)` for a real token's session. A header is refused after 60s: make one per request. Never hand-write the
  JSON, and never use it in production.

Details: https://cosec.ahoo.me/integration/web#services-behind-the-gateway and
https://cosec.ahoo.me/guide/security-model#trust-boundaries.

## Tokens, memberships and API keys

- Tokens are read from the `Authorization` header; the query parameter only with
  `cosec.pipeline.allow-query-token=true`, the `authorization` cookie only with `cosec.pipeline.allow-cookie-token=true`
  (a browser sends a cookie on a cross-site request too, so that application defends against CSRF itself). Issue them
  with the `TokenConverter` bean, or from a login endpoint with `TokenCompositeAuthentication.authenticateAsToken`
  (suspend), which runs your `Authentication` bean for the credentials: `authenticate` is `suspend` and throws to refuse.
- A token's `roles` claim (`role` in the default space, `role@space` elsewhere) and `policies` claim (attached policy
  ids, which must be `attachable`) are the principal's memberships. A token with the claim `cosec.membership:
  "provider"` and no roles or policies takes them from a `MembershipProvider` bean instead; a `JwtTokenConverter` bean
  of your own with `membershipClaimLimit` set issues such tokens when the claims would exceed that many bytes (the
  starter's converter never does). Register the provider before issuing them, or those requests are 503.
- Sessions: issue tokens through `TokenCompositeAuthentication`, which starts a session (a token family); refresh with
  `RefreshTokenAuthentication(verifier, tokenRevocation, tokenFamilies, memberships)`, where `memberships` is
  `MembershipRefresh.CLAIMS` (keeps the old token's memberships: a removed role applies at the next sign-in or after
  `revokeSubject`) or a `MembershipRefresh` that looks the user up again. Only the newest pair refreshes, an older
  one presented again ends the session, and a session lasts `cosec.jwt.session.lifetime` (30 days).
- Refresh and logout routes: set `cosec.authentication.login.refresh.path` and `logout.path` and the starter serves them
  (`POST`, the pair as JSON; `200` pair, `401` token-*, `503` unavailable, `500` error; logout `204`), on Spring MVC and
  WebFlux, never on a gateway. A document must permit an anonymous `POST` of each path, or every refresh is
  `401 token-expired`. A `MembershipRefresh<RefreshTokenCredentials>` bean is used; it refuses by throwing
  `TokenRevokedException`. With no such bean (and no `RefreshTokenAuthentication` of your own) startup fails until
  `cosec.jwt.session.memberships-on-refresh=from-token` states that a refreshed token keeps the old token's memberships (`STA-8`):
  ask the operator which they want (a bean applies a removal within the access token's lifetime, the property at the
  next sign-in, `cosec.jwt.session.lifetime` at the latest) and never write it just to start. Unset, write the routes yourself as below.
- Logout: `TokenRevoker.revoke(accessToken)` ends the session, or `revoke(compositeToken)` once the access token has
  expired. Emergency: `TokenRevoker.revokeSubject(tenant, principal)` revokes every token of a principal issued until now
  (needs the Redis token store).
- API keys: `cosec.api-key.enabled=true`, keys in `cosec.api-key.keys[n]` (`id`, `hash` = SHA-256 hex of the key,
  `principal-id`, `tenant-id` (required), exactly one of `roles` and `uncapped: true`, `expires-at`, `enabled`; any
  other field fails startup) or `cosec.api-key.store=redis`. They arrive in `X-API-Key` (`cosec.pipeline.api-key-header`). A key grants nothing of
  its own: it acts as its principal with the memberships the `MembershipProvider` gives that principal, narrowed to
  `roles` (`uncapped: true`: no narrowing; `[]`: only what `Anyone` bindings give; under a cap, attached policies and
  principal bindings are dropped). No API key gets break-glass. So API keys need a
  `MembershipProvider` bean, or startup fails (IDN-6): with `cosec-cocache`, declare a `MembershipLoader` and the
  starter wires `CoCacheMembershipProvider`. A key whose principal the provider does not know or knows as deactivated is
  `token-invalid`. A user's key is a personal access token; a workload principal's key is a service account's.
  Keys stored before 8.0 are converted with `cosec-migrate api-keys`.
- A token or an API key declares its app (the token's `aud`, set from `TokenSubject.app`; the key's `app`): a host with
  `scope.app-id` (or a gateway route's `cosec-app-id`) refuses a credential declaring another or none. Clients never send an
  app. They send `CoSec-Space-Id` for a non-default space, once (the query string is not read); a space needs a role there, or the request is denied (403
  `scope-rejected`; 401 for an anonymous caller). Details:
  https://cosec.ahoo.me/integration/authentication.

## Adapters and checks in code

The starter registers one filter: `PipelineServletFilter` (MVC), `PipelineWebFilter` (WebFlux) or
`PipelineGatewayFilter` (Gateway). Adapters only translate; status codes and problem+json bodies are decided once, in
the pipeline: 401/403 deny, 429 rate limited, 503 undecidable, 400 ambiguous request. A permitted request carries a
`SecurityContext`: `requireSecurityContext()` (throws) or `securityContextOrNull()` on a `ServerWebExchange`, a Reactor
`ContextView` or an `HttpServletRequest` (extension functions in `ServerWebExchanges`, `ReactiveSecurityContexts`,
`ServletRequests`), or `CoSecSecurityContextHolder.requireSecurityContext()` on the request thread of blocking code. Web details: https://cosec.ahoo.me/integration/web.

To decide in code **for the request in progress** (UI buttons, object-level checks), inject `AuthorizationChecker` and
pass it the `SecurityContext` of that request:

```kotlin
checker.check(securityContext, "order:ship", "order/42")      // suspend
checker.checkMono(securityContext, "order:ship", "order/42")  // cosec-webflux
checker.checkBlocking(securityContext, "order:ship", "order/42") // cosec-webmvc
```

The resource is `type/id`, or a path (`/orders/42`) for an `http:` action. It uses the principal and scope of the
permitted request, runs no rate limits and notifies no listener, and is `false` on any deny, error or outage. Keep
enforcing on the real request. A check is not for a message consumer, a scheduler or any work after the request, and a
`SecurityContext` must never be stored: a check decides with the principal and memberships frozen when the request was
permitted, and checks no age, revocation or change of roles. Once the adapter has ended the request every check of its
context is `false` (`PIP-26`); a transport of your own ends its request by closing the `Outcome` of `Pipeline.evaluate`
(`use { }`, or `close()` when a streamed response completes), and one that never closes it keeps permitting after
logout or after a role is removed; in tests, `PipelineFixture.end(context)` and `PermittedRequest.end(context)`. To test a handler, run it behind `CoSecTestBindings.filter(fixture.pipeline)` (`cosec-testkit`, `me.ahoo.cosec.testkit.webflux` or `.webmvc`) with `fixture.authorization(principal)`; a unit test binds `PermittedRequest.forSubject(...)` (enforce mode) or `withIdentity(IdentityHeader.forSubject(...))` (behind a gateway) with `CoSecTestBindings.withSecurityContext`/`bind`, which refuse a hand-made context (PIP-29). A slice needs the filter bean, not CoSec's auto-configurations. Authorize where the request arrives and carry the decision, not the context, with the work; run a
transport of your own through `Pipeline.evaluate` (`Outcome.security?.securityContext`) rather than verifying tokens by
hand; and when you must verify a token yourself, call `TokenVerifier.verify` and then `TokenRevocation.check` (the
starter's bean), which `verify` alone does not replace. CoSec decides no work the system starts itself in 8.0. A check
applies no route map: `http:POST` on a path decides as `http:POST` even where a route maps it, so check a mapped route by
its action and typed resource (https://cosec.ahoo.me/integration/checks). To record what permitted an action, `checker.decide(...)` (suspend) answers the
`Decision` itself: `Decision.Permit.by` names the statement and, in `via`, the binding and its document. Keep a
`Decision` in the process; never put it into a response. See https://cosec.ahoo.me/integration/checks.

## Hosting the control plane

Two deployments, and these two only (https://cosec.ahoo.me/operations/topologies#the-control-plane): the `cosec-control`
image beside the gateway (https://cosec.ahoo.me/operations/deployment#the-control-plane-host), with `k8s/control/` for
Kubernetes; or `cosec-control` embedded in the IAM service. Both take their caller from the `CoSec-Identity` header
(`cosec.pipeline.mode=enforce`, `cosec.pipeline.caller=forwarded-identity`; the image sets both), which carries no
signature: the host does not start until `cosec.pipeline.identity.only-gateway-can-reach=true` (only the gateway reaches
it) or `only-trusted-callers-can-reach=true` (services call it directly, as the image's service port 8082 allows) states
that the network makes it so. The network is the boundary: a workload that reaches the host is whoever it claims, a
platform administrator included. A service that was in `gateway` mode and now needs the control plane moves to `enforce`
with `caller=forwarded-identity`. From code in the same process, `ControlService` with `ControlCaller.of(securityContext)`
or `ControlCaller.service(...)`; `cosec.control.http.enabled=false` serves no routes. Every instance with the control plane runs
its maintenance passes under their leases; no property turns them off. The service API has no client class of its own yet: a service writes `CoSec-Identity` with
`IdentityHeader.write` (`cosec-document`).

With `cosec-control` (and `cosec-cocache`), the host serves the control API under
`{cosec.control.http.base-path}/v1` (default `/cosec/control/v1`): documents, changes, shadow windows, approval,
break-glass, `simulate`, `explain`, `analyze`. Each call first passes the host pipeline (so the host's documents must
permit the authenticated caller on that path), then is authorized again in app `cosec.control.app-id`
(`cosec-control`) against the built-in `cosec:platform-admin`, `cosec:tenant-admin` and `cosec:tenant-viewer`
policies. Bind the first administrator with `cosec-migrate bootstrap --to-redis ... --platform-admin <principal>`
(a `(platform)`-tenant principal), or with a principal binding of the platform document to `cosec:platform-admin`
in app `cosec-control`. On WebFlux, raise `spring.codec.max-in-memory-size` for large documents; on WebMvc the limit is
10MB (the document cap and 2MB). Workflow: https://cosec.ahoo.me/policies/testing.

The control plane's token is issued for tenant `(platform)` (an administrator) or the tenant's own, and for the app
`cosec.control.app-id`: `TokenSubject(id = "alice", app = "cosec-control", tenant = "(platform)")`. A change is
`POST /changes` (document, base revision, operations), then `:skip-shadow` with a `reason` (the trust root, and a
tenant's administrator on its own document) or `:shadow`, then `:publish`. A console that edits roles sends one `putRole`
per save; a service that mirrors an identity system's roles sends `putRole`, `removeRole` and `putCatalog` the same way
and never writes Redis (https://cosec.ahoo.me/scenarios/roles, https://cosec.ahoo.me/scenarios/external-source).

## Scenarios

Worked examples with documents and settings that run: https://cosec.ahoo.me/scenarios/simple-mode (one tenant, no apps),
`/scenarios/app` (an app without spaces), `/scenarios/spaces` (per-app and shared spaces), `/scenarios/service-account`
(an API key limited to one space), `/scenarios/tenant-owner` (an owner by attachment), `/scenarios/gateway` (which
action names the gateway and the service use), `/scenarios/resource-server` (a service that verifies another service's
tokens and decides in its own code). Tokens are issued for one app (`TokenSubject.app`): the service's
`cosec.authorization.scope.app-id` must be the token's. The model: https://cosec.ahoo.me/guide/model.

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
  `$cosec-extending`. Register the same attribute providers on every instance and on the control-plane host.
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

# Architecture Overview

This page is for people who change or extend CoSec. It describes the structure, the request path, the
decision algorithm, and the invariants every change must keep. File links point at `main`.

## Modules

```mermaid
flowchart BT
    core[cosec-core] --> api[cosec-api]
    jwt[cosec-jwt] --> core
    cocache[cosec-cocache] --> core
    social[cosec-social] --> core
    otel[cosec-opentelemetry] --> core
    openapi[cosec-openapi] --> core
    ip2r[cosec-ip2region] --> core
    kafka[cosec-kafka] --> core
    webflux[cosec-webflux] --> jwt
    webmvc[cosec-webmvc] --> jwt
    gateway[cosec-gateway] --> webflux
    starter[cosec-spring-boot-starter] --> jwt
    starter -. feature variants .-> webflux & webmvc & gateway & cocache & social & otel & openapi & ip2r & kafka
    server[cosec-gateway-server] --> starter
```

- **`cosec-api`** holds the public contracts: `CoSecPrincipal`, `Policy`, `Statement`,
  `ActionMatcher`, `ConditionMatcher`, `Authorization`, `Request`, `SecurityContext`. It depends only on
  Reactor.
- **`cosec-core`** holds every algorithm: the policy engine and matchers, `SimpleAuthorization`, the
  `SecurityPipeline`, the token abstractions, local policy loading, auditing, and JSON serialization.
- Integration modules adapt one technology each. The starter wires them through Gradle feature
  variants, so applications only pull in what they request.

## Request path

```mermaid
sequenceDiagram
    autonumber
    participant A as Web adapter<br>(WebFlux / WebMvc / Gateway)
    participant P as SecurityPipeline
    participant Z as Authorization<br>(Tracing → Auditing → Simple)
    participant R as Policy / permission<br>repositories
    A->>P: evaluate(exchange or servlet request)
    P->>P: RequestParser: path safety, remote IP, request id, attributes
    P->>P: SecurityContextParser: token → principal (off the event loop when it may block)
    P->>Z: decide(request, context)
    Z->>R: global policies, principal policies, role permissions
    R-->>Z: (local cache first, Redis on a miss)
    Z-->>P: AuthorizationDecision(result, verifyContext)
    P-->>A: SecurityDecision(status, result, request, context)
    A->>A: write 4xx/5xx, or continue the chain with the security context
```

- [`SecurityPipeline`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/pipeline/SecurityPipeline.kt)
  owns every transport-independent step, including **all status and error mapping**. Adapters
  ([`ReactiveSecurityFilter`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveSecurityFilter.kt),
  [`AbstractAuthorizationInterceptor`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AbstractAuthorizationInterceptor.kt))
  only translate a `SecurityDecision` into a response. `WebAdapterParityTest` asserts that the adapters
  respond identically.
- Authorization decorators implement
  [`DecisionAuthorization`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/authorization/AuthorizationDecision.kt),
  so the matched `VerifyContext` flows through tracing and auditing as a value.

## Decision algorithm

[`SimpleAuthorization`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/authorization/SimpleAuthorization.kt):

1. A root principal (decided by `RootPrincipalMatcher`) is allowed.
2. A blacklisted request (`BlacklistChecker`) is denied.
3. Three layers are evaluated: global policies, the principal's policies, and the role permissions for the
   request's app and space. In each layer every `deny` is checked before any `allow`. Layers are combined
   deny-first: an `EXPLICIT_DENY` in any layer ends evaluation, and an `ALLOW` is only returned once every
   layer has had the chance to deny.
4. Otherwise the result is `IMPLICIT_DENY`.

A policy's own condition is evaluated exactly once per request, before its statements. A statement matches
when its action and condition both match.

## Invariants

Every change must keep these.

| Invariant | Meaning |
|---|---|
| **Deny-first** | No combination of policies or layers lets an `ALLOW` win over a matched `DENY`. |
| **Fail closed** | Errors deny (`500`, regex timeout `403`). The only exceptions are documented degraded modes: non-strict Redis rate limiters, and the revocation check. Policy and permission caches use strict Redis failure. |
| **No blocking on the event loop** | Blocking work (repository reads from Redis, token revocation) runs on `boundedElastic`. Reads answered from the local cache stay on the caller's thread. Condition matchers that block, such as the Redis rate limiters, implement `BlockingAware`: `SimpleAuthorization` evaluates a layer on `boundedElastic` only when its policies or app permission contain one, so other policies pay no thread hop. |
| **One decision path** | Status codes and error mapping live only in `SecurityPipeline`. Adapters never decide. |
| **Matchers do no I/O** | Matchers only read the request and security context. Path matchers record path variables in the context, and rate limiters hold state by design. Redis rate limiters are the only matchers that do I/O. |
| **Policies are data** | Policies and permissions are JSON. Matchers are created through factories registered by type (`ServiceLoader` or Spring beans). |

## Data and caching

The repositories (`PolicyRepository`, `AppRolePermissionRepository`) are interfaces in `cosec-core`. The
Redis implementations in `cosec-cocache` are built on [CoCache](https://github.com/Ahoo-Wang/CoCache):

- Each cache is pinned to `ttl = FOREVER`, because Redis is the store of record. `CacheTtlPolicyTest`
  guards this.
- Reads try the client-side cache first
  ([`LocalCacheLookup`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/LocalCacheLookup.kt)),
  mirroring CoCache's own local-hit semantics. They fall back to Redis as a whole on any miss.
- The global policy index is updated with `SetElementsCache.addElements` / `removeElements`, atomic Redis
  set operations. `GlobalPolicyIndexConcurrencyIntegrationTest` runs two instances writing concurrently.

## Performance

`SimpleAuthorizationBenchmark` (`cosec-core/src/jmh`) measures the hot path with in-memory repositories,
on JDK 17:

| Scenario | Time | Allocated |
|---|---|---|
| Matched by a global allow | 1.9 µs | 6.1 KB |
| Matched by a role permission | 2.2 µs | 6.6 KB |
| No match | 1.8 µs | 1.8 KB |

Two decisions dominate this: each request parses its path once and shares it across all path matchers, and
deny-first evaluation loops over the source lists without per-item wrappers. Any performance change should
come with a JMH comparison.

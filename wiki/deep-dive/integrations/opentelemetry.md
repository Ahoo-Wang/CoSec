---
title: OpenTelemetry Integration
description: How CoSec integrates with OpenTelemetry for distributed tracing of authorization decisions, including span attributes and reactive subscriber instrumentation.
---

# OpenTelemetry Integration

CoSec provides deep OpenTelemetry integration via the decorator pattern, wrapping the `Authorization` interface with tracing instrumentation. Every authorization decision produces a span with rich attributes capturing the principal, policy, statement, and result.

## Architecture Overview

```mermaid
graph TD
    A["Filter<br>(WebFlux / Gateway)"] --> B["TracingAuthorization<br>(decorator)"]
    B --> C["CoSecMonoTrace<br>(reactive subscriber)"]
    C --> D["CoSecInstrumenter"]
    D --> E["CoSecAttributesExtractor"]
    B --> F["Delegate Authorization"]
    F --> G["SimpleAuthorization"]

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Core Components

### TracingAuthorization

A decorator that wraps any `Authorization` implementation with OpenTelemetry tracing. It implements both `Authorization` and `Delegated<Authorization>`.

```kotlin
class TracingAuthorization(override val delegate: Authorization) :
    Authorization,
    Delegated<Authorization>
```

When `authorize()` is called, it:

1. Captures the current OpenTelemetry `Context`.
2. Creates a `CoSecMonoTrace` that wraps the delegate's `Mono<AuthorizeResult>`.
3. The trace subscriber manages span lifecycle (start, end, error).

### CoSecInstrumenter

Central instrumentation configuration. It creates an OpenTelemetry `Instrumenter` with:

- **Instrumentation name**: `me.ahoo.cosec`
- **Span name**: always `cosec.authorize` (via `CoSecSpanNameExtractor`)
- **Attributes extractor**: `CoSecAttributesExtractor`
- **Version**: read from the package implementation version

```mermaid
sequenceDiagram
    autonumber
    participant Filter
    participant Tracing as TracingAuthorization
    participant MonoTrace as CoSecMonoTrace
    participant Instr as CoSecInstrumenter
    participant Delegate as Delegate Authorization
    participant OTel as OpenTelemetry SDK

    Filter->>Tracing: authorize(request, context)
    Tracing->>Tracing: Capture current OTel Context
    Tracing->>Delegate: authorize(request, context)
    Delegate-->>Tracing: Mono of AuthorizeResult
    Tracing->>MonoTrace: Wrap source Mono
    MonoTrace->>Instr: shouldStart(parentContext, securityContext)
    Instr->>OTel: Check sampling
    OTel-->>Instr: true/false
    alt Should start span
        MonoTrace->>Instr: start(parentContext, securityContext)
        Instr-->>MonoTrace: OTel Context with span
        MonoTrace->>MonoTrace: Subscribe with TraceFilterSubscriber
        Delegate-->>MonoTrace: AuthorizeResult / Error
        MonoTrace->>Instr: end(otelContext, securityContext, result, error)
        Note over Instr: CoSecAttributesExtractor.onEnd()
    else Should not start
        MonoTrace->>MonoTrace: Subscribe without tracing
    end



```

### CoSecAttributesExtractor

Extracts detailed attributes from the security context and authorization result. Attributes are populated in `onEnd()` (after the authorization decision completes), capturing the full decision context.

#### Span Attributes

| Attribute Key | Type | Source | Description |
|--------------|------|--------|-------------|
| `user.id` | string | `principal.id` | Authenticated user ID |
| `user.roles` | string array | `principal.roles` | User's assigned roles |
| `cosec.tenant_id` | string | `securityContext.tenant.tenantId` | Current tenant |
| `cosec.space_id` | string | `request.spaceId` | Current space |
| `cosec.app_id` | string | `request.appId` | Target application ID |
| `device.id` | string | `request.deviceId` | Requesting device ID |
| `cosec.request_id` | string | `request.requestId` | Correlation request ID |
| `cosec.policy` | string array | `principal.policies` | Principal's policy IDs |
| `cosec.authorize.policy.id` | string | `PolicyVerifyContext` | Matched policy ID |
| `cosec.authorize.statement.index` | long | `PolicyVerifyContext` | Matched statement index |
| `cosec.authorize.statement.name` | string | `PolicyVerifyContext` | Matched statement name |
| `cosec.authorize.role.id` | string | `RoleVerifyContext` | Matched role ID |
| `cosec.authorize.permission.id` | string | `RoleVerifyContext` | Matched permission ID |
| `cosec.authorize.result` | string | `VerifyContext.result` | ALLOW / EXPLICIT_DENY / IMPLICIT_DENY |

### CoSecMonoTrace and TraceFilterSubscriber

These classes implement the reactive tracing contract by wrapping a `Mono<AuthorizeResult>`:

- `CoSecMonoTrace` extends `Mono<AuthorizeResult>` and handles span creation on subscribe.
- `TraceFilterSubscriber` extends `CoreSubscriber<AuthorizeResult>` and ends the span on `onComplete()` or `onError()`.

```mermaid
stateDiagram-v2
    [*] --> Subscribed: subscribe()
    Subscribed --> SpanStarted: shouldStart() == true
    Subscribed --> NoTrace: shouldStart() == false
    SpanStarted --> OnNext: authorize result
    SpanStarted --> OnError: exception
    OnNext --> SpanEnded: end(span, result, null)
    OnError --> SpanEnded: end(span, null, error)
    SpanEnded --> [*]
    NoTrace --> [*]

    classDef stateStyle fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    class Subscribed,SpanStarted,NoTrace,OnNext,OnError,SpanEnded stateStyle

```

## Usage in Jaeger / Grafana

The span name `cosec.authorize` can be used to filter traces in your observability backend. The `cosec.authorize.result` attribute lets you quickly find denied requests, while `cosec.authorize.policy.id` and `cosec.authorize.statement.name` pinpoint exactly which policy rule triggered the decision.

## References

- [cosec-opentelemetry/src/main/kotlin/me/ahoo/cosec/opentelemetry/TracingAuthorization.kt:24](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-opentelemetry/src/main/kotlin/me/ahoo/cosec/opentelemetry/TracingAuthorization.kt#L24) -- Decorator wrapping Authorization
- [cosec-opentelemetry/src/main/kotlin/me/ahoo/cosec/opentelemetry/CoSecInstrumenter.kt:36](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-opentelemetry/src/main/kotlin/me/ahoo/cosec/opentelemetry/CoSecInstrumenter.kt#L36) -- Instrumenter and attributes extractor
- [cosec-opentelemetry/src/main/kotlin/me/ahoo/cosec/opentelemetry/AuthorizationMono.kt:23](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-opentelemetry/src/main/kotlin/me/ahoo/cosec/opentelemetry/AuthorizationMono.kt#L23) -- Reactive tracing subscriber
- [cosec-core/src/main/kotlin/me/ahoo/cosec/authorization/SimpleAuthorization.kt:48](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/authorization/SimpleAuthorization.kt#L48) -- Delegate authorization
- [cosec-spring-boot-starter/src/main/kotlin/.../CoSecAutoConfiguration.kt:37](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/CoSecAutoConfiguration.kt#L37) -- Auto-configuration

## Related Pages

- [Spring Cloud Gateway Integration](./spring-cloud-gateway.md)
- [Redis Caching](./redis-caching.md)
- [Performance](../operations/performance.md)
- [Deployment](../operations/deployment.md)

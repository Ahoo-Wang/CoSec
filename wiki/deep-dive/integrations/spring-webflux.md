---
title: Spring WebFlux Integration
description: How CoSec integrates with Spring WebFlux via reactive authorization filters, request parsing, and security context propagation.
---

# Spring WebFlux Integration

CoSec provides first-class reactive integration with Spring WebFlux through a suite of filters and context-propagation utilities built on Project Reactor. Every request passes through a non-blocking authorization pipeline that preserves the reactive contract end to end.

## Architecture Overview

```mermaid
graph TD
    A[Incoming Request] --> B["ReactiveAuthorizationFilter<br>(WebFilter + Ordered)"]
    B --> C["ReactiveSecurityFilter<br>(base class)"]
    C --> D["ReactiveRequestParser"]
    D --> E["ReactiveRequest"]
    C --> F["SecurityContextParser"]
    F --> G["SecurityContext"]
    C --> H["Authorization.authorize()"]
    H --> I{Authorized?}
    I -->|Yes| J["WebFilterChain.filter()"]
    I -->|No| K["HTTP 401 / 403 Response"]
    J --> L["ReactiveSecurityContexts<br>(context propagation)"]

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style H fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style I fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style J fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style K fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Core Components

### ReactiveAuthorizationFilter

The entry point for WebFlux security. It implements both `WebFilter` and `Ordered` with an order of `1000`, placing it after framework filters (CORS, etc.) but before most application logic.

```kotlin
class ReactiveAuthorizationFilter(
    securityContextParser: SecurityContextParser,
    requestParser: RequestParser<ServerWebExchange>,
    authorization: Authorization
) : ReactiveSecurityFilter(securityContextParser, requestParser, authorization),
    WebFilter,
    Ordered
```

- **Order**: `REACTIVE_AUTHORIZATION_FILTER_ORDER = 1000` -- runs after CORS and other infrastructure filters.
- Delegates the real work to [ReactiveSecurityFilter.filterInternal](#reactivesecurityfilter).
- On success, calls `chain.filter(exchange)` so downstream handlers receive an enriched exchange with the principal set.

### ReactiveSecurityFilter

The shared base class that contains all authorization logic. It is also extended by the Spring Cloud Gateway integration.

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Filter as ReactiveSecurityFilter
    participant RParser as ReactiveRequestParser
    participant SCParser as SecurityContextParser
    participant Auth as Authorization
    participant Chain as WebFilterChain

    Client->>Filter: filterInternal(exchange, chain)
    Filter->>RParser: parse(exchange)
    RParser-->>Filter: ReactiveRequest
    Filter->>SCParser: parse(request)
    Note over Filter,SCParser: TokenVerificationException caught<br>falls back to anonymous context
    SCParser-->>Filter: SecurityContext
    Filter->>Auth: authorize(request, securityContext)
    Auth-->>Filter: AuthorizeResult
    alt Authorized
        Filter->>Chain: chain.filter(mutatedExchange)
        Note over Filter: Write SecurityContext to Reactor Context
    else Not Authenticated
        Filter-->>Client: HTTP 401 UNAUTHORIZED
    else Forbidden
        Filter-->>Client: HTTP 403 FORBIDDEN
    else Rate Limited
        Filter-->>Client: HTTP 429 TOO_MANY_REQUESTS
    end



```

The `filterInternal` method handles:

1. **Request parsing** -- converts the `ServerWebExchange` into a CoSec `Request`.
2. **Token verification** -- catches `TokenVerificationException` and falls back to an anonymous `SimpleSecurityContext`.
3. **Authorization decision** -- calls `Authorization.authorize()` and maps the result to HTTP status codes.
4. **Error handling** -- maps `TooManyRequestsException` to 429 and unexpected errors to 500.

### ReactiveRequestParser

Converts a `ServerWebExchange` into a `ReactiveRequest`, extracting path, method, remote IP, origin, referer, and request ID. It also applies any registered `RequestAttributesAppender` instances (e.g., IP geolocation).

### ReactiveRequest

An immutable data class that wraps a `ServerWebExchange` and implements CoSec's `Request` interface. It provides lazy access to headers, query parameters, and cookies from the underlying exchange.

### ReactiveSecurityContexts

Utility object for propagating the `SecurityContext` through Reactor's `Context`:

```mermaid
graph LR
    A["ReactiveSecurityContexts<br>.writeSecurityContext()"] --> B["Reactor Context<br>(ContextView)"]
    B --> C["Downstream Operators<br>.getSecurityContext()"]
    D["ServerWebExchanges<br>.setSecurityContext()"] --> E["ServerWebExchange<br>Attributes"]
    E --> F["Downstream Handlers"]

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

Two propagation channels are used in parallel:

| Channel | Mechanism | Use Case |
|---------|-----------|----------|
| Reactor `Context` | `contextWrite { it.setSecurityContext(ctx) }` | Reactive operators within the same chain |
| `ServerWebExchange` attributes | `exchange.setSecurityContext(ctx)` | Direct access in downstream handlers |

### ReactiveInjectSecurityContextWebFilter

Designed for downstream services behind an API gateway. Instead of performing authorization, it injects the security context from request headers (set by the upstream gateway) without token verification. This avoids redundant JWT verification in microservice-to-microservice calls.

## Filter Chain Order

```mermaid
graph TD
    A["CORS Filter (low order)"] --> B["Other Framework Filters"]
    B --> C["ReactiveInjectSecurityContextWebFilter<br>(HIGHEST_PRECEDENCE + 10)<br>-- or --"]
    B --> D["ReactiveAuthorizationFilter<br>(order = 1000)"]
    D --> E["Application Filters"]
    E --> F["Controller / Handler"]

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

Choose between `ReactiveAuthorizationFilter` and `ReactiveInjectSecurityContextWebFilter` based on whether the service is a front-line service or a downstream microservice.

## References

- [cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveAuthorizationFilter.kt:36](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveAuthorizationFilter.kt#L36) -- Filter entry point
- [cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveSecurityFilter.kt:57](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveSecurityFilter.kt#L57) -- Base class with `filterInternal`
- [cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveRequestParser.kt:27](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveRequestParser.kt#L27) -- Request parsing
- [cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveRequest.kt:22](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveRequest.kt#L22) -- Request data class
- [cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveSecurityContexts.kt:21](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveSecurityContexts.kt#L21) -- Context propagation

## Related Pages

- [Spring WebMVC Integration](./spring-webmvc.md)
- [Spring Cloud Gateway Integration](./spring-cloud-gateway.md)
- [Auto-Configuration](../extending/auto-configuration.md)
- [Testing](../operations/testing.md)

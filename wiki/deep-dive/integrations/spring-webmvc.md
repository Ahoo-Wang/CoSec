---
title: Spring WebMVC Integration
description: How CoSec integrates with Spring MVC servlet applications via jakarta.servlet.Filter, request parsing, and thread-local security context.
---

# Spring WebMVC Integration

CoSec provides a servlet-based integration path for traditional Spring MVC applications through `jakarta.servlet.Filter` implementations. The servlet integration mirrors the reactive WebFlux integration but uses thread-local context propagation instead of Reactor Context.

## Architecture Overview

```mermaid
graph TD
    A["Incoming HTTP Request"] --> B["AuthorizationFilter<br>(jakarta.servlet.Filter)"]
    B --> C["AbstractAuthorizationInterceptor<br>(base class)"]
    C --> D["ServletRequestParser"]
    D --> E["CoSecServletRequest"]
    C --> F["SecurityContextParser"]
    F --> G["SecurityContext"]
    C --> H["Authorization.authorize()"]
    H --> I{Authorized?}
    I -->|Yes| J["FilterChain.doFilter()"]
    I -->|No| K["HTTP 401 / 403 Response"]
    J --> L["SecurityContextHolder<br>(ThreadLocal propagation)"]
    J --> M["ServletRequests<br>(request attribute propagation)"]

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
    style M fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Core Components

### AuthorizationFilter

The servlet filter entry point. It implements `jakarta.servlet.Filter` and extends `AbstractAuthorizationInterceptor` to perform authorization checks on every incoming request.

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Filter as AuthorizationFilter
    participant Interceptor as AbstractAuthorizationInterceptor
    participant RParser as ServletRequestParser
    participant SCParser as SecurityContextParser
    participant Auth as Authorization
    participant Chain as FilterChain

    Client->>Filter: doFilter(request, response, chain)
    Filter->>Interceptor: authorize(httpRequest, httpResponse)
    Interceptor->>RParser: parse(servletRequest)
    RParser-->>Interceptor: CoSecServletRequest
    Interceptor->>SCParser: parse(request)
    Note over Interceptor,SCParser: TokenVerificationException caught<br>falls back to anonymous context
    SCParser-->>Interceptor: SecurityContext
    Interceptor->>Auth: authorize(request, securityContext).block()
    Auth-->>Interceptor: AuthorizeResult
    alt Authorized
        Interceptor-->>Filter: true
        Filter->>Chain: chain.doFilter(request, response)
    else Not Authenticated
        Interceptor-->>Filter: false
        Filter-->>Client: HTTP 401 UNAUTHORIZED
    else Forbidden
        Interceptor-->>Filter: false
        Filter-->>Client: HTTP 403 FORBIDDEN
    end



```

Key behaviors of `AuthorizationFilter.doFilter`:

1. **Delegates** to `AbstractAuthorizationInterceptor.authorize()`.
2. **Catches** `TooManyRequestsException` and returns HTTP 429.
3. **Catches** unexpected exceptions, logs an error, and returns HTTP 500.
4. On successful authorization, calls `chain.doFilter(request, response)`.

### AbstractAuthorizationInterceptor

The base class containing the authorization algorithm. It mirrors the logic in `ReactiveSecurityFilter` -- any changes here should be reflected in the reactive counterpart.

The `authorize()` method:

1. Parses the servlet request into a CoSec `Request` via `ServletRequestParser`.
2. Parses the `SecurityContext` via `SecurityContextParser`, catching `TokenVerificationException`.
3. Stores the context in both `SecurityContextHolder` (thread-local) and as a request attribute.
4. Sets the `X-Request-Id` response header.
5. Calls `authorization.authorize()` and blocks on the result (`.block()`).
6. Returns `false` if denied, `true` if allowed.

### ServletRequestParser

Converts a `jakarta.servlet.http.HttpServletRequest` into a `CoSecServletRequest`, extracting path (via `servletPath`), method, remote IP, origin, referer, and request ID. It also applies registered `RequestAttributesAppender` instances.

### CoSecServletRequest

An immutable data class wrapping an `HttpServletRequest`. Implements CoSec's `Request` interface and `Delegated<HttpServletRequest>`, providing access to headers, query parameters, and cookies from the underlying servlet request.

### InjectSecurityContextFilter

The servlet counterpart of `ReactiveInjectSecurityContextWebFilter`. Designed for downstream services behind an API gateway that have already performed authorization. It uses `SecurityContextParser.ensureParse()` to extract the security context from request headers without token verification.

```mermaid
graph TD
    subgraph "Gateway Service"
        A["AuthorizationFilter<br>(verifies JWT + authorizes)"]
    end
    subgraph "Downstream Service"
        B["InjectSecurityContextFilter<br>(injects context from headers)"]
        B --> C["SecurityContextHolder.setContext()"]
        B --> D["ServletRequests.setSecurityContext()"]
    end
    A -->|"X-Request-Id, Authorization headers"| B

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

### SecurityContextHolder

A thread-local holder for the current security context. Uses `InheritableThreadLocal` so child threads inherit the parent's context. Provides static methods `setContext()`, `context`, `requiredContext`, and `remove()`.

## Context Propagation

Unlike the reactive integration which uses Reactor's `Context`, the servlet integration uses two parallel channels:

| Channel | Mechanism | Scope |
|---------|-----------|-------|
| `SecurityContextHolder` | `InheritableThreadLocal` | Current thread and child threads |
| `HttpServletRequest` attributes | `request.setAttribute()` | Current request lifecycle |

Both are set in `AbstractAuthorizationInterceptor.authorize()` so downstream code can access the security context via either mechanism.

## References

- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AuthorizationFilter.kt:42](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AuthorizationFilter.kt#L42) -- Servlet filter entry point
- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AbstractAuthorizationInterceptor.kt:51](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AbstractAuthorizationInterceptor.kt#L51) -- Base interceptor with authorization logic
- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/ServletRequestParser.kt:31](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/ServletRequestParser.kt#L31) -- Request parsing
- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/CoSecServletRequest.kt:22](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/CoSecServletRequest.kt#L22) -- Request data class
- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/InjectSecurityContextFilter.kt:40](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/InjectSecurityContextFilter.kt#L40) -- Downstream context injection
- [cosec-core/src/main/kotlin/me/ahoo/cosec/context/SecurityContextHolder.kt:26](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/context/SecurityContextHolder.kt#L26) -- Thread-local context holder

## Related Pages

- [Spring WebFlux Integration](./spring-webflux.md)
- [Spring Cloud Gateway Integration](./spring-cloud-gateway.md)
- [Auto-Configuration](../extending/auto-configuration.md)
- [Testing](../operations/testing.md)

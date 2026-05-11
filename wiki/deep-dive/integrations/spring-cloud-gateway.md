---
title: Spring Cloud Gateway Integration
description: How CoSec integrates with Spring Cloud Gateway as a GlobalFilter for centralized authorization at the API gateway layer.
---

# Spring Cloud Gateway Integration

CoSec provides centralized authorization at the API gateway layer through a `GlobalFilter` implementation. Every request passing through the gateway is authorized before being routed to downstream services, which then use inject-only filters to pick up the security context.

## Architecture Overview

```mermaid
graph TD
    A["Client Request"] --> B["Spring Cloud Gateway"]
    B --> C["AuthorizationGatewayFilter<br>(GlobalFilter + Ordered)"]
    C --> D["ReactiveSecurityFilter<br>(inherited base class)"]
    D --> E["Authorization.authorize()"]
    E --> F{Authorized?}
    F -->|Yes| G["Gateway Route Handler"]
    G --> H["Downstream Service"]
    F -->|No| I["HTTP 401 / 403"]
    H --> J["ReactiveInjectSecurityContextWebFilter<br>(reads context from headers)"]

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

```

## Core Components

### AuthorizationGatewayFilter

The central filter that performs authorization at the gateway. It implements `GlobalFilter` and `Ordered`, and extends `ReactiveSecurityFilter`.

```kotlin
class AuthorizationGatewayFilter(
    securityContextParser: SecurityContextParser,
    requestParser: RequestParser<ServerWebExchange>,
    authorization: Authorization
) : GlobalFilter, Ordered,
    ReactiveSecurityFilter(securityContextParser, requestParser, authorization)
```

Key characteristics:

- **Filter order**: `Ordered.HIGHEST_PRECEDENCE + 10` -- runs very early in the gateway filter chain, ensuring authorization is checked before route-specific filters.
- **Request ID propagation**: After successful authorization, it mutates the exchange to add the `X-Request-Id` header so downstream services can correlate requests.
- Inherits all authorization logic from `ReactiveSecurityFilter.filterInternal()`.

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant GW as AuthorizationGatewayFilter
    participant Base as ReactiveSecurityFilter
    participant Auth as Authorization
    participant Chain as GatewayFilterChain
    participant DS as Downstream Service

    Client->>GW: filter(exchange, chain)
    GW->>Base: filterInternal(exchange) { ... }
    Base->>Auth: authorize(request, securityContext)
    Auth-->>Base: AuthorizeResult
    alt Authorized
        Base-->>GW: chain callback
        GW->>GW: Mutate request with X-Request-Id header
        GW->>Chain: chain.filter(mutatedExchange)
        Chain->>DS: Proxied request with headers
        DS->>DS: ReactiveInjectSecurityContextWebFilter
    else Denied
        Base-->>Client: HTTP 401 / 403 / 429
    end



```

### CoSecGatewayAuthorizationAutoConfiguration

Auto-configuration that registers the `AuthorizationGatewayFilter` as a Spring bean. It is conditionally activated when:

- `@ConditionalOnCoSecEnabled` -- `cosec.enabled=true` (default)
- `@ConditionalOnAuthorizationEnabled` -- `cosec.authorization.enabled=true`
- `@ConditionalOnGatewayEnabled` -- `cosec.authorization.gateway.enabled=true`
- `@ConditionalOnClass(AuthorizationGatewayFilter::class)` -- the gateway module is on the classpath

### GatewayServer

The standalone gateway application entry point. It is a standard `@SpringBootApplication` that pulls in all CoSec modules.

```mermaid
graph TD
    subgraph "GatewayServer (Spring Boot Application)"
        A["GatewayServerKt.main()"]
        B["cosec-gateway module"]
        C["cosec-gateway-server module"]
        D["cosec-spring-boot-starter"]
        E["cosec-cocache"]
        F["cosec-opentelemetry"]
        G["cosec-ip2region"]
    end
    A --> C
    C --> B
    C --> D
    C --> E
    C --> F
    C --> G

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Kubernetes Deployment

The gateway is deployed as a containerized application on Kubernetes with health probes, resource limits, and horizontal pod autoscaling.

### Gateway Configuration (ConfigMap)

The `application.yaml` ConfigMap configures routes, CORS, and CoSec-specific settings:

```yaml
cosec:
  authentication:
    enabled: false
  jwt:
    algorithm: hmac256
    secret: FyN0Igd80Gas8stTavArGKOYnS9uLWGA_
  ip2region:
    enabled: false
  authorization:
    local-policy:
      enabled: true
      init-repository: true
    cache:
      policy:
        maximum-size: 100000
      role:
        maximum-size: 100000
```

### Health Probes

The deployment uses three levels of probes:

| Probe Type | Endpoint | Purpose |
|------------|----------|---------|
| `startupProbe` | `/actuator/health` | Confirm the application has started |
| `readinessProbe` | `/actuator/health/readiness` | Ready to receive traffic |
| `livenessProbe` | `/actuator/health/liveness` | Application is still alive |

### Horizontal Pod Autoscaler

The HPA scales between 2 and 10 replicas based on CPU utilization.

```mermaid
graph LR
    A["HPA Controller"] -->|"scale 2-10 replicas"| B["CoSec Gateway Deployment"]
    B --> C["Pod 1"]
    B --> D["Pod 2"]
    B --> E["Pod N..."]
    F["Service<br>(port 80 -> 8080)"] --> B
    G["ConfigMap<br>(application.yaml)"] --> B
    H["Secret<br>(Redis credentials)"] --> B

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style H fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## References

- [cosec-gateway/src/main/kotlin/me/ahoo/cosec/gateway/AuthorizationGatewayFilter.kt:31](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-gateway/src/main/kotlin/me/ahoo/cosec/gateway/AuthorizationGatewayFilter.kt#L31) -- Gateway filter implementation
- [cosec-spring-boot-starter/src/main/kotlin/.../CoSecGatewayAuthorizationAutoConfiguration.kt:43](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/authorization/gateway/CoSecGatewayAuthorizationAutoConfiguration.kt#L43) -- Auto-configuration
- [cosec-gateway-server/src/main/kotlin/.../GatewayServer.kt:24](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-gateway-server/src/main/kotlin/me/ahoo/cosec/gateway/server/GatewayServer.kt#L24) -- Application entry point
- [k8s/cosec-gateway-deployment.yml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-deployment.yml) -- Kubernetes deployment
- [k8s/cosec-gateway-config.yaml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-config.yaml) -- Gateway configuration

## Related Pages

- [Spring WebFlux Integration](./spring-webflux.md)
- [Redis Caching](./redis-caching.md)
- [OpenTelemetry Integration](./opentelemetry.md)
- [Deployment](../operations/deployment.md)

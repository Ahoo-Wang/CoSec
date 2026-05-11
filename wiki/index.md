---
layout: home
hero:
  name: CoSec
  text: Multi-Tenant Reactive Security Framework
  tagline: RBAC-based and Policy-based access control for JVM applications, inspired by AWS IAM
  actions:
    - theme: brand
      text: Get Started
      link: /getting-started/overview
    - theme: alt
      text: Deep Dive
      link: /deep-dive/architecture/security-model
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/CoSec
features:
  - icon: 🛡️
    title: Policy-Based Authorization
    details: AWS IAM-inspired deny-first evaluation engine. Composable policies with ActionMatcher and ConditionMatcher SPI.
    link: /deep-dive/authorization/authorization-flow
  - icon: ⚡
    title: Fully Reactive
    details: Built on Project Reactor with Mono-based auth chain. Integrates with WebFlux, WebMvc, and Spring Cloud Gateway.
    link: /deep-dive/architecture/reactive-design
  - icon: 🏢
    title: Multi-Tenant Native
    details: First-class tenant support with TenantPrincipal, tenant-scoped policies, and role-based permissions per space.
    link: /deep-dive/architecture/multi-tenancy
  - icon: 🔑
    title: JWT & Social Auth
    details: JWT token lifecycle with refresh tokens, plus OAuth social login via JustAuth with 20+ providers.
    link: /deep-dive/authentication/authentication-system
  - icon: 🔌
    title: Extensible SPI
    details: Java ServiceLoader-based extension points for custom ActionMatcher and ConditionMatcher implementations.
    link: /deep-dive/extending/custom-matchers
  - icon: 📊
    title: OpenTelemetry Tracing
    details: Distributed tracing with detailed span attributes for authorization decisions, matched policies, and roles.
    link: /deep-dive/integrations/opentelemetry
---

## Quick Start

```kotlin
// build.gradle.kts
implementation("me.ahoo.cosec:cosec-spring-boot-starter:<version>")
```

```yaml
# application.yaml
cosec:
  jwt:
    algorithm: hmac256
    secret: your-secret-key-here
  authorization:
    local-policy:
      enabled: true
      init-repository: true
```

## Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.3.20 | Primary language |
| Java | 17 | Target JVM |
| Spring Boot | 4.0.5 | Application framework |
| Project Reactor | - | Reactive programming |
| auth0/java-jwt | 4.5.1 | JWT token handling |
| CoCache | 4.0.2 | Distributed Redis caching |
| JustAuth | 1.16.7 | OAuth social authentication |
| OpenTelemetry | 2.26.1 | Observability tracing |

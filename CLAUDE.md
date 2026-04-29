# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build all modules
./gradlew build

# Run all tests
./gradlew test

# Run tests for a single module
./gradlew :cosec-core:test
./gradlew :cosec-api:test

# Run a single test class
./gradlew :cosec-core:test --tests "me.ahoo.cosec.policy.DefaultPolicyEvaluatorTest"

# Run a single test method
./gradlew :cosec-core:test --tests "me.ahoo.cosec.policy.DefaultPolicyEvaluatorTest.evaluate"

# Run Detekt static analysis
./gradlew detekt

# Generate code coverage report
./gradlew :code-coverage-report:codeCoverageReport

# Run JMH benchmarks
./gradlew :cosec-core:jmh -PjmhIncludes=*.SomeBenchmark
```

## Architecture

CoSec is an RBAC-based and Policy-based Multi-Tenant Reactive Security Framework. It's a multi-module Gradle Kotlin DSL project targeting Java 17, built on Spring Boot 4 and Project Reactor.

### Module Dependency Graph

```
cosec-api (core interfaces, no framework deps)
  └── cosec-core (policy evaluation, authentication, authorization logic)
        ├── cosec-jwt (JWT token handling)
        ├── cosec-cocache (Redis caching for policies/permissions)
        ├── cosec-social (OAuth social auth via JustAuth)
        ├── cosec-ip2region (IP geolocation)
        ├── cosec-opentelemetry (observability integration)
        ├── cosec-openapi (Swagger/OpenAPI integration)
        ├── cosec-webflux (reactive WebFilter integration)
        ├── cosec-webmvc (servlet filter integration)
        ├── cosec-gateway (Spring Cloud Gateway GlobalFilter)
        └── cosec-spring-boot-starter (auto-configuration, aggregates all above)
              └── cosec-gateway-server (standalone gateway app, not published)
```

- `cosec-dependencies` and `cosec-bom` manage version catalogs (versions in `gradle/libs.versions.toml`)
- `cosec-gateway-server` is the only non-publishable module (not a library)

### Core Security Model (cosec-api)

The security model follows an AWS IAM-like policy design:

- **`CoSecPrincipal`** — represents a user/agent with id, roles, policies, and attributes. Extends `java.security.Principal`. Root users bypass all checks.
- **`Authentication<C, P>`** — reactive interface (`Mono`-based) that verifies credentials and returns a `CoSecPrincipal`.
- **`Authorization`** — reactive interface that evaluates a `Request` against a `SecurityContext` and returns `AuthorizeResult` (ALLOW/EXPLICIT_DENY/IMPLICIT_DENY).
- **`Policy`** — collection of `Statement`s with an optional `ConditionMatcher`. Policy evaluation: check condition → DENY statements first → ALLOW statements. Default is implicit deny.
- **`Statement`** — single permission rule with `Effect` (ALLOW/DENY), `ActionMatcher`, and `ConditionMatcher`.
- **`SecurityContext`** — holds the current principal, tenant info, and mutable attributes.

### Authorization Flow (SimpleAuthorization)

1. Root user check → immediate ALLOW
2. Blacklist check → block if listed
3. Global policies evaluation (DENY-first, then ALLOW)
4. Principal-specific policies evaluation
5. Role-based app permissions evaluation
6. Default: IMPLICIT_DENY

### SPI Extension Points

Custom policy matchers use Java SPI (META-INF/services):

- **`ActionMatcherFactory`** → create `ActionMatcher` implementations. Built-in: `PathActionMatcher`, `AllActionMatcher`, `CompositeActionMatcher`
- **`ConditionMatcherFactory`** → create `ConditionMatcher` implementations. Built-in: path-based (`Eq`, `Contains`, `StartsWith`, `EndsWith`, `In`, `Regular`), context-based (`Authenticated`, `InRole`, `InTenant`), rate limiters, OGNL/SpEL expressions

To add a custom matcher: implement the factory interface, register in `META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory` (or condition variant).

### Spring Integration

- **WebFlux** (`cosec-webflux`): `ReactiveAuthorizationFilter` implements `WebFilter`, wraps `ReactiveSecurityFilter`
- **Gateway** (`cosec-gateway`): `AuthorizationGatewayFilter` implements Spring Cloud Gateway `GlobalFilter`
- **WebMvc** (`cosec-webmvc`): servlet filter-based integration
- **Auto-configuration** (`cosec-spring-boot-starter`): `CoSecAutoConfiguration` with conditional features (`@ConditionalOnCoSecEnabled`, `@ConditionalOnJwtEnabled`, etc.). Properties prefixed with `cosec.*`

### Multi-Tenancy

Tenants are first-class in the security model. `TenantPrincipal` extends `CoSecPrincipal` with tenant context. `SecurityContext` extends `TenantCapable`. Policies are tenant-scoped via the `Tenant` interface.

## Conventions

- Kotlin 2.x with `-Xjsr305=strict` and `-Xjvm-default=all-compatibility` compiler flags
- All public APIs are interfaces defined in `cosec-api`; implementations live in `cosec-core`
- Reactive throughout: core interfaces return `Mono<T>` (Project Reactor)
- Tests use JUnit 5, MockK, Hamcrest, and FluentAssert (`me.ahoo.test:fluent-assert-core`)
- Detekt for static analysis with auto-correct enabled; config at `config/detekt/detekt.yml`
- Dependency versions centralized in `gradle/libs.versions.toml` (version catalog)
- Every module has JMH benchmark support via `me.champeau.jmh` plugin
- Logging via `io.github.oshai:kotlin-logging-jvm` + Logback

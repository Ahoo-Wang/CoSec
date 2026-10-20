# CoSec

Multi-tenant, reactive security for Spring: IAM-style policies and role-based permissions, evaluated
deny-first for every request.

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/CoSec.svg)](https://github.com/Ahoo-Wang/CoSec/releases)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.cosec/cosec-core)](https://central.sonatype.com/artifact/me.ahoo.cosec/cosec-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/b3133cf684a74192a55abbefe2a0759a)](https://www.codacy.com/gh/Ahoo-Wang/CoSec/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/CoSec&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/CoSec/branch/main/graph/badge.svg?token=AL0RyJbMZv)](https://codecov.io/gh/Ahoo-Wang/CoSec)
[![Integration Test Status](https://github.com/Ahoo-Wang/CoSec/actions/workflows/integration-test.yml/badge.svg)](https://github.com/Ahoo-Wang/CoSec)
[![CodeQL](https://github.com/Ahoo-Wang/CoSec/actions/workflows/codeql.yml/badge.svg)](https://github.com/Ahoo-Wang/CoSec/actions/workflows/codeql.yml)
[![Awesome Kotlin Badge](https://kotlin.link/awesome-kotlin.svg)](https://github.com/KotlinBy/awesome-kotlin)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/CoSec)

> [Documentation](https://cosec.ahoo.me/) | [中文文档](https://cosec.ahoo.me/zh/)

## Why CoSec

- **Policies as data.** JSON policies with path actions and composable conditions (roles, tenants, headers,
  IP, expressions). Change them at runtime; every instance sees the change immediately.
- **Rate limits.** Per-IP, per-user or shared quotas on allowed requests, counted cluster-wide in Redis, with
  `RateLimit-*` and `Retry-After` headers.
- **Deny wins, everywhere.** Global policies, the principal's own policies and role permissions are combined
  deny-first, unmatched requests are denied, and errors deny.
- **Multi-tenant and role-based.** Tenant-aware principals, plus per-application permission catalogs
  granted to roles per space.
- **One pipeline, three stacks.** Spring WebFlux, Spring MVC and Spring Cloud Gateway share the same
  security pipeline and respond identically.
- **Fast and reactive.** Built on Project Reactor. About 2 µs per decision, with no event-loop blocking.
- **Auditable.** An audit event (log or Kafka) and an OpenTelemetry span for every decision, naming the
  policy or permission that decided it.

## How a decision is made

```mermaid
flowchart LR
    R([Request + JWT]) --> BL{Blacklisted?}
    BL -->|yes| DENY([EXPLICIT_DENY])
    BL -->|no| ROOT{Root principal?}
    ROOT -->|yes| ALLOW([ALLOW])
    ROOT -->|no| L["Global policies<br>Principal policies<br>Role permissions"]
    L --> D{Any DENY matched?}
    D -->|yes| DENY
    D -->|no| A{Any ALLOW matched?}
    A -->|yes| ALLOW
    A -->|no| IMP([IMPLICIT_DENY])
```

Denied anonymous callers get `401`, authenticated callers `403`, and rate-limited requests `429`.

## Quick start

```kotlin
dependencies {
    implementation(platform("me.ahoo.cosec:cosec-bom:<version>"))
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.cosec:webflux-support") } // or webmvc-support / gateway-support
    }
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.cosec:cache-support") }   // Redis-backed policy storage
    }
}
```

```yaml
cosec:
  jwt:
    secret: ${COSEC_JWT_SECRET}
  authorization:
    local-policy:
      enabled: true          # classpath:cosec-policy/*-policy.json
      init-repository: true
```

```json
{
  "id": "app", "name": "App", "category": "app", "description": "", "type": "global", "tenantId": "(platform)",
  "statements": [
    { "name": "public", "action": ["/auth/login", "/actuator/health"] },
    { "name": "authenticated", "action": "/api/**", "condition": { "authenticated": {} } },
    { "name": "admins-only", "effect": "deny", "action": "/api/admin/**",
      "condition": { "inRole": { "value": "admin", "negate": true } } }
  ]
}
```

Continue with the [Quick Start](https://cosec.ahoo.me/guide/quick-start) to issue tokens, then the
[policy reference](https://cosec.ahoo.me/guide/policies).

## Modules

The starter brings the core, JWT support and auto-configuration; each integration (WebFlux, MVC, Gateway,
Redis, OAuth, OpenTelemetry, OpenAPI, ip2region, Kafka) is an opt-in Gradle capability. See
[Modules](https://cosec.ahoo.me/guide/introduction#modules) for the full list. A ready-to-run gateway is
published as the container image `ahoowang/cosec-gateway`.

## Documentation

- **Guide**: [Introduction](https://cosec.ahoo.me/guide/introduction) ·
  [Quick Start](https://cosec.ahoo.me/guide/quick-start) · [Policies](https://cosec.ahoo.me/guide/policies) ·
  [Configuration](https://cosec.ahoo.me/guide/configuration) · [Authentication](https://cosec.ahoo.me/guide/authentication) ·
  [Web Integration](https://cosec.ahoo.me/guide/web-integration) · [Operations](https://cosec.ahoo.me/guide/operations) ·
  [Troubleshooting](https://cosec.ahoo.me/guide/troubleshooting)
- **Architecture**: [Overview](https://cosec.ahoo.me/architecture/overview) · [Extending](https://cosec.ahoo.me/architecture/extending)
- **Releases**: [GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases)

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow, quality gates and
release process. This project follows a [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md); do not open public issues for them.

## License

CoSec is released under the [Apache License 2.0](LICENSE). Its policy model is inspired by
[AWS IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/introduction.html).

# Introduction

CoSec is a reactive, multi-tenant security framework for Spring Boot applications and gateways. It
answers one question for every request: **may this principal perform this action, in this context?**
The answer comes from IAM-style policies (inspired by
[AWS IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/introduction.html)) and role-based app
permissions, evaluated deny-first.

## Core concepts

| Concept | What it is |
|---|---|
| **Principal** | Who is calling: an id, its roles, the policies attached to it, and free-form attributes. Anonymous callers are a principal too (id `(0)`). Carried in a JWT. |
| **Tenant** | The tenant the principal acts in. `(0)` is the default tenant and `(platform)` is the platform tenant; any other id is a user tenant. |
| **Request** | The transport-neutral view of an HTTP request: path, method, remote IP, origin, referer, headers, app id (`CoSec-App-Id`), space id (`CoSec-Space-Id`), and device id (`CoSec-Device-Id`). |
| **Policy** | A named set of **statements** with a type: `GLOBAL` applies to every request; `SYSTEM` (platform-managed) and `CUSTOM` (user-defined) apply only to principals that list them in their `policies`. An optional policy-level condition gates the whole policy. |
| **Statement** | One rule: an **effect** (`ALLOW` or `DENY`), an **action** (which requests it covers, usually path patterns) and an optional **condition** (when it applies). |
| **App permission** | Per application (`appId`), a catalog of permissions grouped for UI display. **Roles** grant permissions by id, per space. |

Policies and permissions are JSON documents. See [Policies](./policies.md) for the language.

## How a decision is made

```mermaid
flowchart TD
    R([Request + SecurityContext]) --> ROOT{Root principal?}
    ROOT -->|yes| ALLOW([ALLOW])
    ROOT -->|no| BL{Blacklisted?}
    BL -->|yes| DENY([EXPLICIT_DENY])
    BL -->|no| G[Global policies]
    G --> P[Principal's policies]
    P --> RP[Role permissions for appId / spaceId]
    RP --> D{Any layer matched a DENY?}
    D -->|yes| DENY
    D -->|no| A{Any layer matched an ALLOW?}
    A -->|yes| ALLOW
    A -->|no| IMPLICIT([IMPLICIT_DENY])
```

- **Deny wins everywhere.** A `DENY` matched in any layer overrides an `ALLOW` matched in any other
  layer. A global `ALLOW` cannot override a principal- or role-level `DENY`.
- **Default deny.** If nothing matches, the request is denied (`IMPLICIT_DENY`).
- **Fail closed.** Errors deny: an unreachable policy store, a failed condition, or an exhausted
  regex time budget never let a request through.

The web integration turns the decision into a response: `401` for anonymous callers, `403` for
authenticated ones, `429` when a rate limit is hit. See [Web Integration](./web-integration.md).

## Modules

| Module | Purpose |
|---|---|
| `cosec-api` | Interfaces only: principal, policy, authorization, request, tenant. |
| `cosec-core` | Policy engine, matchers, authorization, security pipeline, local policy loading, auditing. |
| `cosec-jwt` | JWT issuing and verification. |
| `cosec-webflux` / `cosec-webmvc` / `cosec-gateway` | Adapters for Spring WebFlux, Spring MVC and Spring Cloud Gateway. |
| `cosec-cocache` | Redis-backed policy, permission and revoked-token storage with a local cache ([CoCache](https://github.com/Ahoo-Wang/CoCache)); Redis rate limiters. |
| `cosec-social` | OAuth social login via [JustAuth](https://github.com/justauth/JustAuth). |
| `cosec-opentelemetry` · `cosec-openapi` · `cosec-ip2region` · `cosec-kafka` | Tracing, policy generation from OpenAPI, IP geolocation, audit events to Kafka. |
| `cosec-spring-boot-starter` | Auto-configuration for all of the above, enabled per feature. |
| `cosec-gateway-server` | A ready-to-run gateway built from the modules above (not published as a library). |

Next: [Quick Start](./quick-start.md).

---
title: "Introduction"
description: "What CoSec is, what it decides, and whether it fits your system."
---

# Introduction

For anyone evaluating CoSec: architects, tech leads and developers deciding whether to adopt it. This page explains what CoSec decides on every request, where it sits in your application, and which modules you need, so you can judge the fit before writing any code.

## What CoSec does

At its core CoSec is a decision function:

```text
decide(principal, action, resource, environment) -> decision
```

| Input | In CoSec | Comes from |
|---|---|---|
| principal | who is calling: id, tenant, roles, attached policies | the verified token or API key |
| action and resource | the request's method and path, in an app and a space | the request |
| environment | client IP, headers, time and other request attributes | the request and the clock |
| decision | `ALLOW`, `EXPLICIT_DENY`, `IMPLICIT_DENY`, `TOO_MANY_REQUESTS`, or an error | |

Around that function, CoSec provides what it takes to use it in production:

- **Access rules as data.** Policies and permissions are JSON documents in a store (Redis, or your own
  repository). Changing who may do what is a data change, not a redeploy.
- **Tenants, apps and spaces.** Principals belong to tenants, and roles are granted per app and per space (a
  team, a project), so one user can be an admin in one space and a reader in another.
- **Safe defaults.** Nothing is allowed unless a rule allows it, a deny always beats an allow, and an error
  denies.
- **Tokens.** JWT issuing and verification, refresh, logout and revocation, and API keys for machine clients.
- **Operations.** Rate limiting, audit events, metrics, tracing, a shadow mode to try policy changes on live
  traffic, and an explanation of why any request was decided the way it was.

The terms are defined in [Concepts](./concepts.md), and the rules the function follows in
[Security model](./security-model.md).

### What CoSec does not do

- **It is not an identity provider.** CoSec verifies tokens and issues them for a principal you hand it, but
  checking passwords, running login pages and federating identities is your application's job, or an IdP's.
  OAuth social login is an optional add-on.
- **It does not filter data.** CoSec decides whether a request may reach a handler. Which rows and fields the
  handler returns stays in your code. A policy can tie a path to the principal (`/users/#{principal.id}/**`),
  but it cannot look up who owns a database record.
- **It does not verify the app or space a client names.** Both come from request headers. Where that is not
  acceptable, bind them at the gateway; see [Trust boundaries](./security-model.md#trust-boundaries).
- **It does not isolate tenants on its own.** Your policies decide what each tenant may do; see
  [Tenant isolation](./security-model.md#tenant-isolation).
- **It has no administration UI.** You write policies and grants into the store with your own tools or the
  repository APIs.

## When to use it

CoSec fits when:

- you run several Spring services, often behind Spring Cloud Gateway, and want one definition of access;
- you serve several tenants or teams that need different roles in different spaces;
- access rules change at runtime and must not require a redeploy;
- your rules are about requests (`DELETE /orders/**`, `GET /reports/*`) rather than annotations on methods.

It is a poor fit when your rules depend mainly on the data being returned (use row-level security in the
database or rules in your domain code), or when your services are not on the JVM.

## How it fits into your application

CoSec runs as a filter in front of your handlers: a `WebFilter` in WebFlux, a servlet `Filter` in Spring MVC,
or a `GlobalFilter` in Spring Cloud Gateway. For every request the filter:

1. parses the request and reads the principal from its token (an invalid or missing token means the anonymous
   principal);
2. asks the authorization for a decision;
3. passes an allowed request on, with the security context available to your code, or answers a rejected one
   itself with `401`, `403`, `429` or `500`.

All three filters run the same pipeline, so a gateway and the services behind it decide alike. Your
application supplies the login endpoint that issues tokens, the policies, and, if you use roles, the app
permissions and role grants. See [Web integration](../integration/web.md).

## Modules

Applications depend on `cosec-spring-boot-starter` and add integrations as Gradle capabilities (see
[Quick start](./quick-start.md#add-the-dependency)). Every application needs one web adapter; most also need
`cache-support` for the policy store.

| You need | Capability | Module |
|---|---|---|
| Authorization in a Spring WebFlux service | `webflux-support` | `cosec-webflux` |
| Authorization in a Spring MVC service | `webmvc-support` | `cosec-webmvc` |
| Authorization in Spring Cloud Gateway | `gateway-support` | `cosec-gateway` |
| Policies, role grants and revoked tokens in Redis, and cluster-wide rate limits | `cache-support` | `cosec-cocache` |
| Social login through OAuth | `oauth-support` | `cosec-social` |
| A tracing span per decision | `opentelemetry-support` | `cosec-opentelemetry` |
| An authorization timer by result | `micrometer-support` | `cosec-micrometer` |
| Policies generated from your OpenAPI description | `openapi-support` | `cosec-openapi` |
| Audit events sent to Kafka | `kafka-support` | `cosec-kafka` |
| The client's region as a request attribute | `ip2region-support` | `cosec-ip2region` |

The starter always brings `cosec-api` (the public contracts), `cosec-core` (the decision engine, policy
loading, auditing, rate limiting) and `cosec-jwt` (JWT tokens). `cosec-bom` aligns the versions.
`cosec-model` and `cosec-engine` are published for the decision engine planned for 8.0; nothing depends on them yet,
so applications do not need them.
`cosec-gateway-server` is a ready-to-run gateway built from these modules: deploy it, do not depend on it (see
[Gateway](../integration/web.md#gateway)).

Without Spring Boot, use `cosec-core` directly: build a `SimpleAuthorization` from your own
`PolicyRepository` and `AppRolePermissionRepository`, and call it from your own transport. See
[Repositories](../architecture/extending.md#repositories).

## Where to go next

Pick the path that matches what you are doing. Every page on it ends with a link to the next one.

| You are | Read, in order |
|---|---|
| Evaluating CoSec | this page → [Concepts](./concepts.md) → [Security model](./security-model.md) → [Architecture overview](../architecture/overview.md) |
| A developer integrating it | [Quick start](./quick-start.md) → [Web integration](../integration/web.md) → [Authentication](../integration/authentication.md) → [Checks in code](../integration/checks.md) → [Configuration](../operations/configuration.md) |
| A policy author | [Concepts](./concepts.md) → [Policy language](../policies/language.md) → [Role permissions](../policies/role-permissions.md) → [Rate limiting](../policies/rate-limiting.md) → [Testing policies](../policies/testing.md) |
| Running it in production | [Configuration](../operations/configuration.md) → [Storage and caching](../operations/storage.md) → [Observability](../operations/observability.md) → [Troubleshooting](../operations/troubleshooting.md) → [Upgrading](../operations/upgrading.md) |
| Contributing to CoSec | [Architecture overview](../architecture/overview.md) → [Extending CoSec](../architecture/extending.md) → [`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/CONTRIBUTING.md) |

Everyone should read the [Security model](./security-model.md) before going to production.

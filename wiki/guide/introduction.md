---
title: "Introduction"
description: "What CoSec is, what it decides, and whether it fits your system."
---

# Introduction

For anyone evaluating CoSec: architects, tech leads and developers deciding whether to adopt it. This page explains what CoSec decides on every request, where it sits in your application, and which modules you need, so you can judge the fit before writing any code.

## What CoSec does

At its core CoSec is a decision function:

```text
decide(principal, scope, action, resource, environment) -> decision
```

| Input | In CoSec | Comes from |
|---|---|---|
| principal | who is calling: id, tenant, roles in each space, attached policies | the verified token or API key, or your membership provider |
| scope | the tenant, app and space the request acts in, and the principal's roles there | the principal's tenant, the service's app or the request, checked against the principal's roles |
| action and resource | `http:GET` on `http:path:/orders/42`, or, with a route map, `order:read` on `order/42` | the request |
| environment | client IP, time, device and external attributes such as a region or a blacklist entry | the request, the clock and your attribute providers |
| decision | `Permit` (possibly with rate limit obligations), `Deny`, or `Indeterminate` | |

Around that function, CoSec provides what it takes to use it in production:

- **Access rules as data.** Policies and the bindings that apply them are JSON documents: one platform document, one per
  app and one per tenant. Changing who may do what is a data change, not a redeploy.
- **A control plane.** Changes are proposed, checked, compiled, tried on live traffic in a shadow window, approved by a
  second person when they grant more than their author may grant alone, and published as revisions you can diff and roll back.
- **Tenants, apps and spaces.** Principals belong to tenants, and roles apply per space (a team, a project) inside an
  app, so one user can be an admin in one space and a reader in another.
- **Safe defaults.** Nothing is allowed unless a rule allows it, a deny always beats an allow, and data that cannot be
  read denies.
- **Tokens.** JWT issuing and verification, refresh, logout and revocation, and API keys for machine clients.
- **Operations.** Rate limiting, decision events for audit, metrics and tracing, and an explanation of why any request
  was decided the way it was.

The terms are defined in [Concepts](./concepts.md), and the rules the function follows in
[Security model](./security-model.md).

### What CoSec does not do

- **It is not an identity provider.** CoSec verifies tokens and issues them for a principal you hand it, but
  checking passwords, running login pages and federating identities is your application's job, or an IdP's.
  OAuth social login is an optional add-on.
- **It does not filter data.** CoSec decides whether a request may reach a handler. Which rows and fields the
  handler returns stays in your code. A policy can tie a resource to the principal (a resource variable compared with
  `subject.id`), but it cannot look up who owns a database record unless an attribute provider fetches that for it.
- **It does not verify the app a client names unless you bind it.** A service bound to one app
  (`cosec.authorization.scope.app-id`) rejects a request that names another; a service that is not bound accepts the
  app the request names. The space is checked: a request can name a space other than
  the default only if the principal has a role there. See [Trust boundaries](./security-model.md#trust-boundaries).
- **It has no administration UI.** Documents are published through the control plane's HTTP API, or with
  `cosec-migrate`.

## When to use it

CoSec fits when:

- you run several Spring services, often behind Spring Cloud Gateway, and want one definition of access;
- you serve several tenants or teams that need different roles in different spaces;
- access rules change at runtime, must not require a redeploy, and need review before they go live;
- your rules are about requests (`DELETE /orders/**`, `order:cancel`) rather than annotations on methods.

It is a poor fit when your rules depend mainly on the data being returned (use row-level security in the
database or rules in your domain code), or when your services are not on the JVM.

## How it fits into your application

CoSec runs as a filter in front of your handlers: a `WebFilter` in WebFlux, a servlet `Filter` in Spring MVC,
or a `GlobalFilter` in Spring Cloud Gateway. For every request the filter hands the request to one coroutine pipeline,
which:

1. authenticates the token or API key (an invalid or missing one means the anonymous principal);
2. resolves the principal's memberships and the scope;
3. reads the compiled policy snapshot from memory and asks the engine for a decision;
4. runs the obligations of a permit (rate limits) and tells the decision listeners;
5. returns an outcome: an allowed request goes on to your handler, with the security context available to your code;
   any other gets a `401`, `403`, `429`, `503` or `400` with an `application/problem+json` body.

All three filters run the same pipeline, so a gateway and the services behind it decide alike. Your application
supplies the login endpoint that issues tokens. Policies are published to Redis through the control plane, which can
run in one of your services. See [Web integration](../integration/web.md) and the
[architecture overview](../architecture/overview.md).

## Modules

Applications depend on `cosec-spring-boot-starter` and add integrations as Gradle capabilities (see
[Quick start](./quick-start.md#add-the-dependency)). Every application needs one web adapter; most also need
`cache-support`, which makes Redis the store of record for policy snapshots and revoked tokens.

| You need | Capability | Module |
|---|---|---|
| Authorization in a Spring WebFlux service | `webflux-support` | `cosec-webflux` |
| Authorization in a Spring MVC service | `webmvc-support` | `cosec-webmvc` |
| Authorization in Spring Cloud Gateway | `gateway-support` | `cosec-gateway` |
| Policy snapshots, revoked tokens, shadow windows and cluster-wide rate limits in Redis | `cache-support` | `cosec-cocache` |
| The control plane: publishing, history, rollback, shadow windows, approvals, simulation | `control-support` | `cosec-control` (needs `cache-support`) |
| Social login through OAuth | `oauth-support` | `cosec-social` |
| A tracing span per decision | `opentelemetry-support` | `cosec-opentelemetry` |
| A timer per decision, by result | `micrometer-support` | `cosec-micrometer` |
| Route maps and app documents generated from your OpenAPI description | `openapi-support` | `cosec-openapi` |
| Decision events, and optionally control-plane changes, sent to Kafka | `kafka-support` | `cosec-kafka` |
| The client's region as an external attribute | `ip2region-support` | `cosec-ip2region` |

The starter always brings `cosec-runtime` (the coroutine request pipeline), `cosec-core` (documents, snapshots,
tokens, memberships, API keys), `cosec-engine` and `cosec-model` (the pure decision engine), `cosec-api` (the public
contracts) and `cosec-jwt` (JWT tokens). `cosec-bom` aligns the versions of every published module.

Two artifacts are not dependencies of an application:

- `cosec-migrate` is a command-line tool: it converts 7.x data into 8.0 documents and publishes them, and binds the
  control plane's first platform administrator (`bootstrap`). See [Upgrading](../operations/upgrading.md).
- `cosec-gateway-server` is a ready-to-run gateway built from these modules: deploy it, do not depend on it (see
  [Deployment](../operations/deployment.md)).

Without Spring Boot, assemble a `Pipeline` from `PipelineComponents` yourself and call it from your own transport. See
[Extending CoSec](../architecture/extending.md).

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

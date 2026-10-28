---
title: "Quick Start"
description: "Protect a Spring Boot service with CoSec, from dependency to first denied request."
---

# Quick Start

For developers adding CoSec to a Spring Boot service for the first time. Follow the steps in order and you end with a running WebFlux service that issues tokens, permits what a policy grants, and denies everything else with a `401` or `403` problem.

## Prerequisites

- JDK 17 or later, and a Spring Boot 4 project built with Gradle.
- A Redis server on `localhost:6379` whose `maxmemory-policy` is `noeviction`, Redis's default. CoSec keeps its
  policy documents in Redis and refuses to start when Redis may evict them. For example:

  ```bash
  docker run -d --name redis -p 6379:6379 redis:8
  docker exec redis redis-cli CONFIG GET maxmemory-policy   # noeviction
  ```

- A checkout of [CoSec](https://github.com/Ahoo-Wang/CoSec) at the version you use, to build the `cosec-migrate`
  tool that publishes the first policy document.

## Add the dependency

```kotlin
dependencies {
    implementation(platform("me.ahoo.cosec:cosec-bom:<version>"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("me.ahoo.cosec:cosec-spring-boot-starter")
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.cosec:webflux-support") } // or webmvc-support, gateway-support
    }
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.cosec:cache-support") }   // documents and revocations in Redis
    }
}
```

Declare the starter three times: once on its own, which brings the CoSec API onto your compile classpath, and
once per capability. A capability adds only its own module; a single block that requires two capabilities does
not resolve. Which capability adds what is listed in [Modules](./introduction.md#modules).

`cache-support` is not optional here: with JWT on, which is the default, the application does not start without a
store for [token revocation](../integration/authentication.md#logout-and-revocation), and `cache-support` provides
it.

## Configure

`src/main/resources/application.yaml`:

```yaml
spring:
  data:
    redis:
      host: localhost
cosec:
  jwt:
    secret: ${COSEC_JWT_SECRET}   # HMAC key that signs and verifies tokens
```

Every other setting has a working default; all of them are listed in
[Configuration](../operations/configuration.md). Two of the defaults matter for this guide:

- the policy documents are read from Redis under the key prefix `cosec`, the one `cosec-migrate` writes to;
- the route map is read from `classpath:cosec-routes.json`, and a missing file fails startup. You add it next.

## Map routes to actions

A route map turns a request's method and path into an action and a resource, so policies can say `order:delete`
instead of repeating paths. `src/main/resources/cosec-routes.json`:

```json
{
  "service": "order",
  "routes": [
    { "method": "GET",    "path": "/orders/{orderId}", "action": "order:read",   "resource": "order/{orderId}" },
    { "method": "DELETE", "path": "/orders/{orderId}", "action": "order:delete", "resource": "order/{orderId}" }
  ]
}
```

`GET /orders/42` is now the action `order:read` on the resource `order/42`. A request that no route matches keeps
the action `http:<METHOD>` on its path: `GET /hello` is `http:GET` on `http:path:/hello`. To run without a route
map, set `cosec.authorization.routes.location` to an empty value; every request is then in the `http` namespace.
The format and its rules are in [Route maps](../integration/web.md#route-maps).

## Write a first policy

CoSec decides from **documents**: one platform document that applies to every tenant, plus optional tenant and app
documents. Each holds policies and the bindings that say to whom they apply. Create a directory `documents` with
`documents/platform.json`:

```json
{
  "schema": 2,
  "revision": 1,
  "publishedAt": "2026-01-01T00:00:00Z",
  "policies": [
    {
      "id": "public",
      "statements": [
        { "name": "hello-and-token", "effect": "allow", "action": "http:GET",
          "resource": ["http:path:/hello", "http:path:/token"] }
      ]
    },
    {
      "id": "members",
      "statements": [
        { "name": "orders", "effect": "allow", "action": "order:*",
          "condition": { "eq": ["subject.authenticated", true] } }
      ]
    },
    {
      "id": "no-delete",
      "statements": [
        { "name": "no-delete", "effect": "deny", "action": "order:delete" }
      ]
    }
  ],
  "bindings": [
    { "id": "public",    "subject": "*", "app": "*", "space": "*", "policy": "public" },
    { "id": "members",   "subject": "*", "app": "*", "space": "*", "policy": "members" },
    { "id": "no-delete", "subject": "*", "app": "*", "space": "*", "policy": "no-delete" }
  ]
}
```

The three bindings apply each policy to anyone (`"subject": "*"`), in any app and space. Together they say:

- anyone may `GET /hello` and `/token`;
- authenticated callers may perform any `order:` action;
- nobody may `order:delete`. This statement wins over `members`, because a matching DENY always wins.

Everything else, such as `GET /admin`, matches nothing and is denied. The fields are in
[Policy language](../policies/language.md); why a DENY wins and what is denied by default is in the
[Security model](./security-model.md#evaluation-order).

## Publish the platform document

Build `cosec-migrate` from your CoSec checkout:

```bash
./gradlew :migrate:installDist
# the tool is migration/migrate/build/install/cosec-migrate/bin/cosec-migrate
```

`publish` reads a directory the way `convert` writes it: it publishes exactly the files that `report.json` lists,
each with the SHA-256 recorded there, so that nothing is published that was edited afterwards. For a document you
wrote yourself, write that report:

```bash
SHA=$(sha256sum documents/platform.json | cut -d' ' -f1)   # macOS: shasum -a 256
printf '{"accepted":true,"outputSha256":{"platform.json":"%s"}}\n' "$SHA" > documents/report.json
cosec-migrate publish --to-redis redis://localhost:6379 --in documents
```

It prints `Published platform` and writes the document as the first revision of the platform document. `publish`
never overwrites: once a platform document exists, later changes go through the control plane
([Storage](../operations/storage.md), [Testing policies](../policies/testing.md)).

Until a platform document is published, CoSec fails closed: it answers every request with `503`, the control
plane's routes included, so the first platform document always comes from `cosec-migrate publish`. An absent
platform document is never read as an empty one, which would silently drop every platform DENY
([Fail closed](./security-model.md#fail-closed)). Meanwhile the application reports itself not ready
(`/actuator/health/readiness`, with Spring Boot Actuator), so a load balancer sends it no traffic
([Readiness](../operations/deployment.md#readiness)).

## Issue a token

Add a controller with the endpoints the policy mentions, and a demo endpoint that issues tokens:

```kotlin
@RestController
class DemoController(private val tokenConverter: TokenConverter) {
    @GetMapping("/hello")
    fun hello() = "hello"

    @GetMapping("/orders/{id}")
    fun order(@PathVariable id: String) = "order-$id"

    @DeleteMapping("/orders/{id}")
    fun delete(@PathVariable id: String) = "deleted $id"

    // Demo only: issues a token for any user id, without checking a password.
    @GetMapping("/token")
    fun token(@RequestParam user: String): CompositeToken =
        tokenConverter.toToken(SimplePrincipal(id = user))
}
```

The starter configures `TokenConverter` (`me.ahoo.cosec.token`) from `cosec.jwt`. `SimplePrincipal` is in
`me.ahoo.cosec.principal`, `CompositeToken` in `me.ahoo.cosec.api.token`; the response carries an
`accessToken` and a `refreshToken`. A real login endpoint checks credentials before it issues a token; see
[Logging in](../integration/authentication.md#logging-in).

## Send requests

Start the application with a secret, for example `COSEC_JWT_SECRET=$(openssl rand -hex 32)`, then:

```bash
curl -i localhost:8080/hello
```

`200 hello`: the `public` statement permits it, even without a token.

```bash
curl -i localhost:8080/orders/1
```

`401` with an `application/problem+json` body: `members` requires an authenticated caller, and nothing permits the
anonymous one.

```json
{"type":"https://cosec.ahoo.me/problems/unauthorized","title":"Unauthorized","status":401,"code":"UNAUTHORIZED","requestId":"..."}
```

```bash
TOKEN=$(curl -s 'localhost:8080/token?user=alice' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/orders/1
```

`200 order-1`: your first permitted request.

```bash
curl -i -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/orders/1
```

`403` with the problem type `forbidden`: your first denied request. Alice is authenticated, so the status is `403`,
not `401`.

```bash
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/admin
```

`403 forbidden` again: no statement mentions `/admin`, so it is the implicit deny. The response is the same as for
the DENY: a client never learns whether a DENY exists.

```bash
curl -i -H "Authorization: Bearer not-a-token" localhost:8080/orders/1
```

`401` with the problem type `token-invalid`: a rejected token makes the caller anonymous, and the problem names why.
The same token on `/hello` still gets `200`.

Every response carries a `CoSec-Request-Id` header, the same id as the body's `requestId`. All statuses and
problem types are listed in [Responses and status codes](../integration/web.md#responses-and-status-codes).

## Next steps

- Grant access by role, per app and space: [Role permissions](../policies/role-permissions.md).
- Change documents safely through the control plane, with simulation and shadow windows: [Testing policies](../policies/testing.md).
- Replace the demo `/token` endpoint with a real login, refresh and logout: [Authentication](../integration/authentication.md).
- Read the principal and scope in your handlers: [Web integration](../integration/web.md).
- Find out why a request was permitted or denied: [Explaining a decision](../integration/checks.md#explaining-a-decision).
- Before production, read [Security model](./security-model.md).

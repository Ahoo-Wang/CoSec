---
title: "Quick Start"
description: "Protect a Spring Boot service with CoSec, from dependency to first denied request."
---

# Quick Start

For developers adding CoSec to a Spring Boot service for the first time. Follow the steps in order and you end with a running service that issues tokens, allows what a policy grants and denies everything else.

## Prerequisites

- JDK 17 or later, and a Spring Boot 4 project built with Gradle.
- A Redis server on `localhost:6379` that holds no data of an older CoSec version, for example
  `docker run -d -p 6379:6379 redis:7`.

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
        capabilities { requireCapability("me.ahoo.cosec:cache-support") }   // policies stored in Redis
    }
}
```

Declare the starter three times: once on its own, which brings the CoSec API onto your compile classpath, and
once per capability. A capability adds only its own module; a single block that requires two capabilities does
not resolve. Which capability adds what is listed in [Modules](./introduction.md#modules).

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
  authorization:
    local-policy:
      enabled: true               # read classpath:cosec-policy/*-policy.json
      init-repository: true       # write those policies into Redis at startup
      force-refresh: true         # overwrite them on every start, so edits take effect (development only)
```

Without `force-refresh`, a policy that is already in Redis is left alone, and later edits to the file are
ignored. See [Local policy files](../operations/storage.md#local-policy-files) for how file policies reach the
store. Every other setting has a working default; all of them are listed in
[Configuration](../operations/configuration.md).

## Write a first policy

`src/main/resources/cosec-policy/app-policy.json`:

```json
{
  "id": "app",
  "name": "App",
  "category": "app",
  "description": "Who may call what",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "public",
      "action": ["/hello", "/token"]
    },
    {
      "name": "signed-in",
      "action": "/orders/**",
      "condition": { "authenticated": {} }
    },
    {
      "name": "no-delete",
      "effect": "deny",
      "action": { "path": { "pattern": "/orders/**", "method": "DELETE" } }
    }
  ]
}
```

A `global` policy applies to every request. Its three statements say:

- anyone may call `/hello` and `/token`;
- signed-in callers may call anything under `/orders`;
- nobody may `DELETE` under `/orders`. This statement wins over the second one, because a deny always beats an
  allow.

Everything else, such as `/admin`, matches nothing and is denied. The statement syntax is in
[Policy language](../policies/language.md).

## Issue a token

Add a controller with the endpoints the policy mentions, and a demo endpoint that issues tokens:

```kotlin
@RestController
class DemoController(private val tokenConverter: TokenConverter) {
    @GetMapping("/hello")
    fun hello() = "hello"

    @GetMapping("/orders")
    fun orders() = listOf("order-1")

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

`200 hello`: the `public` statement allows it, even without a token.

```bash
curl -i localhost:8080/orders
```

`401` with `{"authorized":false,"reason":"Implicit Deny"}`: `signed-in` requires a principal, and nothing else
allows the anonymous caller.

```bash
TOKEN=$(curl -s 'localhost:8080/token?user=alice' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/orders
```

`200 ["order-1"]`: your first allowed request.

```bash
curl -i -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/orders/1
```

`403` with `{"authorized":false,"reason":"Explicit Deny"}`: your first denied request. Alice is signed in, so
the status is `403`, not `401`, and the reason is `Explicit Deny` because the `no-delete` statement matched.

```bash
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/admin
```

`403` with `Implicit Deny`: no statement mentions `/admin`.

All outcomes are listed in [Decisions](./concepts.md#decisions), and the status each one gets in
[Responses and status codes](../integration/web.md#responses-and-status-codes).

## Next steps

- Grant access by role instead of by path for everyone: [Role permissions](../policies/role-permissions.md).
- Replace the demo `/token` endpoint with a real login, refresh and logout: [Authentication](../integration/authentication.md).
- Read the user and tenant in your handlers, and the full status mapping: [Web integration](../integration/web.md).
- Find out why a request was allowed or denied: [Troubleshooting](../operations/troubleshooting.md#find-out-what-decided).
- Before production, read [Security model](./security-model.md).

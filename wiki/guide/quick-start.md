---
title: "Quick Start"
description: "Protect a Spring Boot service with CoSec, from dependency to first denied request."
---

# Quick Start

For developers adding CoSec to a Spring Boot service for the first time. Follow the steps in order and you end with a running WebFlux service that issues tokens, permits what a policy grants, and denies everything else with a `401` or `403` problem.

## Prerequisites

- JDK 21 or later, and a Spring Boot 4 project built with Gradle.
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
    implementation("me.ahoo.cosec:cosec-spring-boot")
    implementation("me.ahoo.cosec:cosec-webflux")                      // or cosec-webmvc, cosec-gateway
    implementation("me.ahoo.cosec:cosec-cocache")                      // documents and revocations in Redis
    implementation("org.springframework.boot:spring-boot-data-redis")
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
}
```

`cosec-spring-boot` is the auto-configuration; it wires every CoSec module it finds on the classpath, so add the
modules you need next to it. Which module adds what, and what each needs with it, is listed in
[Modules](./introduction.md#modules). The web adapter is not optional: a WebFlux application without `cosec-webflux`
fails at startup with a message naming the artifact (STA-1).

`cosec-cocache` is not optional here either: with JWT on, which is the default, the application does not start
without a store for [token revocation](../integration/authentication.md#logout-and-revocation), and `cosec-cocache`
provides it.

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
- no route map is loaded until a property names one, so a request is `http:<METHOD>` on its path, and the policy below
  is written that way. Route maps, which name routes with business actions, are in
  [Authorizing business actions](../scenarios/route-maps.md).

## Write a first policy

CoSec decides from **documents**: one platform document that applies to every tenant, plus optional tenant and app
documents. Each holds policies and the bindings that say to whom they apply. Create a directory `documents` with
`documents/platform.json`:

<<< @/examples/quick-start/documents/platform.json [documents/platform.json]

The four bindings apply each policy to anyone (`"subject": "*"`), in any app and space. Together they say:

- anyone may `GET /hello` and `/token`;
- authenticated callers may make any request under `/orders/`;
- nobody may `DELETE` under `/orders/`. This statement wins over `members`, because a matching DENY always wins;
- authenticated callers may reach the control API's paths. This opens a path, not a permission: the control plane
  authorizes every step again as the caller, and it is what lets you change this document later without starting over
  ([Change a document later](#change-a-document-later)).

Everything else, such as `GET /admin`, matches nothing and is denied. The fields are in
[Policy language](../policies/language.md); why a DENY wins and what is denied by default is in the
[Security model](./security-model.md#evaluation-order). The scenarios in
[`wiki/examples/quick-start`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/quick-start/scenarios) check
every sentence above against these documents on every build. Roles come in
[Simple mode](../scenarios/simple-mode.md).

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
        tokenConverter.toToken(TokenSubject(id = user, app = null))
}
```

The starter configures `TokenConverter` (`me.ahoo.cosec.login.token`) from `cosec.jwt`. `TokenSubject` is in the
same package, `CompositeToken` in `me.ahoo.cosec.api.token`; the response carries an
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

## Change a document later

`cosec-migrate publish` writes only the first revision and never overwrites. Every later change goes through the
control plane, which checks and compiles a change before it publishes, so adding a policy is a few calls. The platform
document above already lets signed-in callers reach the control API; without that, the only way back is to empty Redis.

1. **Add the control plane.** It comes in a module and needs `cosec-cocache`, which you have:

   ```kotlin
   implementation("me.ahoo.cosec:cosec-control")
   ```

2. **Name the first administrator.** This binds one principal of the `(platform)` tenant to the built-in
   `cosec:platform-admin` policy:

   ```bash
   cosec-migrate bootstrap --to-redis redis://localhost:6379 --platform-admin alice
   ```

3. **Issue her a token for the control plane.** Control-plane tokens are for the tenant `(platform)` and the app
   `cosec-control` (`cosec.control.app-id`). For this guide, print one at startup; a real deployment issues
   `(platform)` tokens only from a source users cannot influence:

   ```kotlin
   @Bean
   fun platformToken(tokenConverter: TokenConverter) = ApplicationRunner {
       // Demo only: anyone who can run this can administer the deployment.
       val token = tokenConverter.toToken(TokenSubject(id = "alice", app = "cosec-control", tenant = "(platform)"))
       println("platform token: ${token.accessToken}")
   }
   ```

4. **Propose, skip the shadow window, publish.** A change is based on a revision you have seen (here `1`, the
   first), so that it cannot overwrite a newer one. This one lets signed-in callers read `/reports/`:

   <<< @/examples/quick-start/changes/add-reports.json [add-reports.json]

   ```bash
   CONTROL=http://localhost:8080/cosec/control/v1
   AUTH="Authorization: Bearer $PLATFORM_TOKEN"
   ID=$(curl -s -X POST "$CONTROL/changes" -H "$AUTH" -H 'Content-Type: application/json' -d @add-reports.json | jq -r .id)
   curl -s -X POST "$CONTROL/changes/$ID:skip-shadow" -H "$AUTH" -H 'Content-Type: application/json' -d '{"reason":"first change"}'
   curl -s -X POST "$CONTROL/changes/$ID:publish" -H "$AUTH"
   ```

   A shadow window would compare the change with live traffic first, which a guide without traffic cannot wait for;
   the administrator is the trust root and may skip it. `GET $CONTROL/documents/platform` now returns revision `2`, and
   `GET /reports/1` with Alice's ordinary token is permitted (the route has no handler here, so it answers `404`, not
   `403`).

The workflow, its checks, shadow windows and approvals are in [Testing policies](../policies/testing.md). Document
changes by tenants and roles follow the same steps: [System and custom roles](../scenarios/roles.md).

## Next steps

- See what you can leave out, then grow: [Simple mode](../scenarios/simple-mode.md), and the scenarios from
  [An app without spaces](../scenarios/app.md) to [Gateway topologies](../scenarios/gateway.md).
- Grant access by role, per app and space: [Role permissions](../policies/role-permissions.md).
- Change documents safely through the control plane, with simulation and shadow windows: [Testing policies](../policies/testing.md).
- Replace the demo `/token` endpoint with a real login, refresh and logout: [Authentication](../integration/authentication.md).
- Read the principal and scope in your handlers: [Web integration](../integration/web.md).
- Find out why a request was permitted or denied: [Explaining a decision](../integration/checks.md#explaining-a-decision).
- Before production, read [Security model](./security-model.md).

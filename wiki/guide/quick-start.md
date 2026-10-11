# Quick Start

This guide secures a Spring WebFlux service with JWT authentication and JSON policies stored in Redis.
For Spring MVC or Spring Cloud Gateway, swap the capability in step 1; everything else is the same.

**Prerequisites:** JDK 17+, Spring Boot 4, and a Redis server.

## 1. Add the dependency

The starter exposes each integration as a Gradle *feature variant*. Request the ones you need:

```kotlin
dependencies {
    implementation(platform("me.ahoo.cosec:cosec-bom:<version>"))
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities {
            requireCapability("me.ahoo.cosec:webflux-support") // or webmvc-support / gateway-support
        }
    }
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities {
            requireCapability("me.ahoo.cosec:cache-support") // Redis-backed policy and permission storage
        }
    }
}
```

Available capabilities: `webflux-support`, `webmvc-support`, `gateway-support`, `cache-support`,
`oauth-support`, `opentelemetry-support`, `openapi-support`, `ip2region-support`, `kafka-support`.

With Maven (no feature variants), add the starter plus the matching modules directly, for example
`cosec-webflux` and `cosec-cocache`.

::: tip Why Redis?
Authorization reads policies through a `PolicyRepository` and role permissions through an
`AppRolePermissionRepository`. `cache-support` provides Redis-backed implementations with an
in-process cache. To keep policies elsewhere, provide your own beans of those two types instead.
:::

## 2. Configure

```yaml
spring:
  data:
    redis:
      host: localhost
cosec:
  jwt:
    secret: ${COSEC_JWT_SECRET} # HMAC secret used to sign and verify tokens
  authorization:
    local-policy:
      enabled: true           # load policies from classpath:cosec-policy/*-policy.json
      init-repository: true   # write them into the PolicyRepository (Redis) on startup
```

Every other setting has a working default. See [Configuration](./configuration.md).

## 3. Write a policy

Create `src/main/resources/cosec-policy/app-policy.json`:

```json
{
  "id": "app",
  "name": "App",
  "category": "app",
  "description": "Public endpoints and authenticated access",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "public",
      "action": ["/auth/login", "/actuator/health"]
    },
    {
      "name": "authenticated",
      "action": "/api/**",
      "condition": { "authenticated": {} }
    },
    {
      "name": "no-admin-from-outside",
      "effect": "deny",
      "action": "/api/admin/**",
      "condition": { "in": { "part": "request.header.X-Network", "value": ["public"] } }
    }
  ]
}
```

A statement allows by default; `"effect": "deny"` makes it a deny. Because deny wins, the third
statement blocks `/api/admin/**` for public traffic even though the second statement allows `/api/**`.

## 4. Issue tokens

CoSec verifies tokens on every request; issuing them is your login endpoint's job. Implement an
`Authentication` for your credentials and the starter registers it automatically:

```kotlin
data class PasswordCredentials(val username: String, val password: String) : Credentials

@Component
class PasswordAuthentication(private val users: UserService) :
    Authentication<PasswordCredentials, CoSecPrincipal> {
    override val supportCredentials = PasswordCredentials::class.java

    override fun authenticate(credentials: PasswordCredentials): Mono<CoSecPrincipal> =
        users.verify(credentials.username, credentials.password)
            .map { user -> SimplePrincipal(id = user.id, roles = user.roles) }
}

@RestController
class LoginController(private val authentication: TokenCompositeAuthentication) {
    @PostMapping("/auth/login")
    fun login(@RequestBody credentials: PasswordCredentials): Mono<out CompositeToken> =
        authentication.authenticateAsToken(credentials)
}
```

The response carries an `accessToken` and a `refreshToken`. Clients send the access token in the
`Authorization` header (`Bearer <token>`) or an `authorization` cookie.

## 5. Try it

```bash
curl -i localhost:8080/api/orders                            # 401: anonymous, implicit deny
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/orders   # 200
```

Rejected requests get a JSON body explaining why, for example
`{"authorized":false,"reason":"Implicit Deny"}`.

## Next steps

- [Policies](./policies.md): actions, conditions, rate limits, roles and permissions.
- [Authentication](./authentication.md): refresh, logout and revocation, social login, multi-tenancy.
- [Web Integration](./web-integration.md): status codes, accessing the security context, gateways.

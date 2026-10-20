---
name: cosec-integration
description: Integrate CoSec into Spring Boot by selecting WebFlux, WebMVC, or Gateway support and configuring JWT, local policies, Redis, auditing, or optional integrations. Do not use for policy-only authoring or diagnosis.
---

# CoSec Spring Boot Integration

Make the smallest integration that fits the target application. Preserve its build tool, dependency-management style, HTTP stack, configuration format, and existing security boundaries.

## Inspect first

Determine:

- Spring WebFlux, Spring MVC, or Spring Cloud Gateway; add exactly the matching CoSec transport.
- The CoSec version already managed by a BOM, version catalog, parent, or lockfile. Never copy a version from this skill.
- Whether policies are local-only or must initialize a shared repository.
- Whether Redis, OAuth, IP enrichment, tracing, OpenAPI, Kafka, or token revocation is actually required.

The starter already brings `cosec-core` and `cosec-jwt`. Prefer its Gradle feature capabilities because they include the integration and its runtime dependencies:

| Need | Starter capability | CoSec module |
|---|---|---|
| Spring MVC | `webmvc-support` | `cosec-webmvc` |
| Spring WebFlux | `webflux-support` | `cosec-webflux` |
| Spring Cloud Gateway | `gateway-support` | `cosec-gateway` |
| Social OAuth | `oauth-support` | `cosec-social` |
| Redis cache, revocation, cluster-wide rate limits | `cache-support` | `cosec-cocache` |
| IP region | `ip2region-support` | `cosec-ip2region` |
| OpenTelemetry | `opentelemetry-support` | `cosec-opentelemetry` |
| Micrometer metrics | `micrometer-support` | `cosec-micrometer` |
| OpenAPI | `openapi-support` | `cosec-openapi` |
| Kafka audit sink | `kafka-support` | `cosec-kafka` |

For Gradle, select a capability on the starter dependency:

```kotlin
implementation(platform(libs.cosec.bom))
implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.cosec:webflux-support")
    }
}
```

Declare the starter again for each additional capability. The module column is a mapping, not a complete dependency set. With Maven or a build that cannot select Gradle variants, use the BOM, starter, one transport module, and mirror the selected feature's runtime dependencies. In the current build, cache support also needs `org.springframework.boot:spring-boot-data-redis` and `me.ahoo.cocache:cocache-spring-boot-starter`, OpenAPI support needs `org.springframework.boot:spring-boot-starter-actuator` and `org.springdoc:springdoc-openapi-starter-common`, and Kafka support needs `org.springframework.boot:spring-boot-starter-kafka`.

## Minimal configuration

```yaml
cosec:
  jwt:
    algorithm: hmac256
    secret: ${COSEC_JWT_SECRET}
  authorization:
    local-policy:
      enabled: true
      init-repository: true
      locations:
        - classpath:cosec-policy/*-policy.json
```

Keep the JWT secret outside source control. A blank secret fails startup while JWT is enabled. Supported algorithms are `hmac256`, `hmac384`, and `hmac512`; issuer and verifier must agree.

`LocalPolicyLoader` only parses files; authorization reads `PolicyRepository`. This bootstrap configuration therefore initializes the repository at startup and requires `PolicyRepository` and `AppRolePermissionRepository` beans. The cache capability supplies Redis-backed implementations; otherwise provide them explicitly.

Create policies under `src/main/resources/cosec-policy/` using the configured pattern. A safe complete skeleton is:

```json
{
  "id": "service-access",
  "name": "Service Access",
  "category": "access",
  "description": "Service endpoint access",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "Health",
      "action": "/actuator/health"
    }
  ]
}
```

Use `$cosec-policy-author` when the requested policy is more than this bootstrap rule.

## Optional behavior

- `local-policy.init-repository` is `false` by default. The bootstrap above enables it because the repository is not otherwise populated from local files. Disable it only when policies are already stored elsewhere; use `force-refresh` only when overwriting repository state is intended.
- `cosec.jwt.token-revocation.enabled=true` needs a real `TokenStore`; the cache capability supplies the Redis-backed implementation. Without it, logout does not revoke tokens and the starter logs a warning.
- Rate limit rules load from `cosec.limiter.locations` (default `classpath:cosec-rate-limit/*-rate-limit.json`); an unreadable rule fails startup. The starter wraps its auto-configured `Authorization` with `RateLimitingAuthorization`. With the cache capability and a `StringRedisTemplate` the counters are cluster-wide in Redis (prefix `cosec.limiter.key-prefix`), otherwise per JVM. Use per-rule `strictFailure` when Redis outage behavior must fail closed; `cosec.limiter.enabled=false` turns rate limiting off. The `rateLimiter`/`redisRateLimiter` policy conditions were removed in 6.1.0; migrate them to rules.
- With the starter's auto-configured `Authorization`, audit logging is enabled by default. The logging sink records denies at WARN and allows at DEBUG under `me.ahoo.cosec.audit`; the Kafka capability replaces it when a unique Kafka template is available. A custom `Authorization` bean is not wrapped automatically, so compose it with `AuditingAuthorization` explicitly.
- To decide in code (UI buttons, message consumers), inject `AuthorizationChecker` and call `check(context, path, method)` or `checkAll(context, mapOf(key to CheckRequest(...)))`. Checks reuse the policies, are not audited or rate limited, inherit the current request's app, space and IP, and deny on errors; keep enforcing on the real request.
- For machine clients, enable `cosec.api-key.enabled=true` and configure `cosec.api-key.keys[n]` with `id`, `hash` (SHA-256 hex from `ApiKeys.hash(key)`; never the key itself), `principal-id`, `roles`, `policies`, optional `tenant-id` and `expires-at`. Keys arrive in `X-API-Key` (configurable) and take precedence over JWTs; invalid keys deny with `401 Token Invalid`. With `cache-support`, `cosec.api-key.store=redis` shares keys across instances; issue and revoke them through the `RedisApiKeyStore` bean (`issue`, `save`, `revoke`). A custom `ApiKeyStore` that does I/O needs `cosec.api-key.store-blocking=true`.
- Set `cosec.authorization.remote-ip.max-trusted-index` to the real trusted-proxy depth. `0` ignores `X-Forwarded-For`.
- For role-based access (CoSec 7+), grants are scoped to an app and a space. Write them through `RolePermissionCache` with `AppSpacedRoleId(appId, roleId, spaceId)` (Redis key `<prefix>:app:role:permission:<appId>:<roleId>[@<spaceId>]`; app ids must not contain `:`, role ids not `@`). Issue token or API key roles as `role` for the default space and `role@spaceId` for a space, only after checking the principal's membership: `CoSec-Space-Id` only selects among the spaces the credential states. Clients send `CoSec-App-Id` (and `CoSec-Space-Id` outside the default space). When upgrading from 6.x, migrate the old app-less grants with `RolePermissionKeyMigration.migrate(appId)` (dry run first) before 7.0 serves traffic, and `deleteLegacy()` once verified.

## Verification

Start the application or run its context test, then verify:

- policy resources are loaded with no skipped-file errors;
- an allowed anonymous request succeeds;
- an unmatched anonymous request returns 401;
- an authenticated but denied request returns 403;
- a request over a rate limit rule returns 429 with `Retry-After` and `RateLimit-*` headers;
- Redis-backed features work from more than one instance when cluster-wide behavior is required.

In a CoSec checkout, treat `cosec-spring-boot-starter/build.gradle.kts` and the `*Properties.kt` / auto-configuration classes under `cosec-spring-boot-starter/src/main/kotlin` as the source of truth for capabilities, defaults, and activation conditions.

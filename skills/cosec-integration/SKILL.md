---
name: cosec-integration
description: "Guide developers through integrating CoSec security framework into Spring Boot applications. Use this skill whenever the user asks about adding CoSec to a project, configuring CoSec properties, setting up JWT authentication, enabling authorization filters, adding Redis caching for policies, or integrating CoSec with WebFlux, WebMVC, or Spring Cloud Gateway."
---

# CoSec Integration Guide

This skill guides you through integrating CoSec into a Spring Boot application. CoSec is a reactive, multi-tenant RBAC + Policy-based security framework built on Spring Boot and Project Reactor.

## Step 1: Add Dependencies

CoSec modules are published to Maven Central under the `me.ahoo.cosec` group. Use the BOM for version management.

### Gradle (Kotlin DSL)

```kotlin
// gradle/libs.versions.toml
[versions]
cosec = "4.3.6"  // check latest at https://central.sonatype.com/artifact/me.ahoo.cosec/cosec-bom

[libraries]
cosec-bom = { module = "me.ahoo.cosec:cosec-bom", version.ref = "cosec" }
cosec-spring-boot-starter = { module = "me.ahoo.cosec:cosec-spring-boot-starter" }
cosec-webflux = { module = "me.ahoo.cosec:cosec-webflux" }
cosec-webmvc = { module = "me.ahoo.cosec:cosec-webmvc" }
cosec-gateway = { module = "me.ahoo.cosec:cosec-gateway" }
cosec-jwt = { module = "me.ahoo.cosec:cosec-jwt" }
cosec-cocache = { module = "me.ahoo.cosec:cosec-cocache" }
cosec-social = { module = "me.ahoo.cosec:cosec-social" }
cosec-ip2region = { module = "me.ahoo.cosec:cosec-ip2region" }
cosec-opentelemetry = { module = "me.ahoo.cosec:cosec-opentelemetry" }
cosec-openapi = { module = "me.ahoo.cosec:cosec-openapi" }
```

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform(libs.cosec.bom))
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)   // for WebFlux apps
    // implementation(libs.cosec.webmvc)  // for Servlet apps
    // implementation(libs.cosec.gateway) // for Spring Cloud Gateway
    implementation(libs.cosec.jwt)       // JWT token handling
    // implementation(libs.cosec.cocache) // Redis policy caching
}
```

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>me.ahoo.cosec</groupId>
            <artifactId>cosec-bom</artifactId>
            <version>4.3.6</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>me.ahoo.cosec</groupId>
        <artifactId>cosec-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>me.ahoo.cosec</groupId>
        <artifactId>cosec-webflux</artifactId>
    </dependency>
    <dependency>
        <groupId>me.ahoo.cosec</groupId>
        <artifactId>cosec-jwt</artifactId>
    </dependency>
</dependencies>
```

### Module Selection Guide

| Use Case | Required Modules |
|----------|-----------------|
| Basic WebFlux app | `cosec-spring-boot-starter` + `cosec-webflux` |
| Servlet/WebMVC app | `cosec-spring-boot-starter` + `cosec-webmvc` |
| API Gateway | `cosec-spring-boot-starter` + `cosec-gateway` |
| JWT authentication | add `cosec-jwt` |
| Redis policy caching | add `cosec-cocache` |
| Social OAuth login | add `cosec-social` |
| IP geolocation conditions | add `cosec-ip2region` |
| Authorization tracing | add `cosec-opentelemetry` |
| OpenAPI/Swagger integration | add `cosec-openapi` |

## Step 2: Configure application.yaml

```yaml
cosec:
  enabled: true                        # master switch (default: true)
  authentication:
    enabled: true                      # enable authentication
  authorization:
    enabled: true                      # enable authorization (default: true)
    local-policy:
      enabled: true                    # load policies from local JSON files
      locations: classpath:cosec-policy/*-policy.json
      init-repository: true            # push local policies to repository on startup
  jwt:
    algorithm: hmac256                 # hmac256, hmac384, hmac512, rsa256, etc.
    secret: your-256-bit-secret-key    # required for HMAC algorithms
```

### Property Reference

| Property | Default | Description |
|----------|---------|-------------|
| `cosec.enabled` | `true` | Master enable/disable switch |
| `cosec.authentication.enabled` | — | Enable/disable authentication |
| `cosec.authorization.enabled` | `true` | Enable/disable authorization |
| `cosec.authorization.local-policy.enabled` | `false` | Load policies from local JSON files |
| `cosec.authorization.local-policy.locations` | `classpath:cosec-policy/*-policy.json` | Resource patterns for policy files |
| `cosec.authorization.local-policy.init-repository` | `false` | Push local policies to repository on startup |
| `cosec.jwt.algorithm` | — | JWT algorithm (hmac256, rsa256, etc.) |
| `cosec.jwt.secret` | — | JWT secret key (for HMAC algorithms) |
| `cosec.ip2region.enabled` | `false` | Enable IP geolocation |
| `cosec.openapi.enabled` | `false` | Enable OpenAPI integration |

## Step 3: Create Policy Files

Place policy JSON files in `src/main/resources/cosec-policy/`. Files must match the pattern `*-policy.json`.

### Minimal Example — Public Health Endpoint

```json
{
  "id": "(health-probe)",
  "name": "Health Probe",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "actuator",
      "action": [
        "/actuator/health",
        "/actuator/health/readiness",
        "/actuator/health/liveness"
      ]
    }
  ]
}
```

### Typical Example — API with Auth

```json
{
  "id": "api-policy",
  "name": "API Policy",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "PublicEndpoints",
      "action": ["/auth/login", "/auth/register"]
    },
    {
      "name": "AuthenticatedApi",
      "action": "/api/**",
      "condition": { "authenticated": {} }
    },
    {
      "name": "AdminEndpoints",
      "action": "/admin/**",
      "condition": { "inRole": { "value": "admin" } }
    }
  ]
}
```

See the `cosec-policy-author` skill for full policy JSON reference.

## Step 4: Spring Boot Application

No special annotations or configuration classes needed. CoSec auto-configures everything based on classpath and properties.

```kotlin
@SpringBootApplication
class MyApp

fun main(args: Array<String>) {
    runApplication<MyApp>(*args)
}
```

The auto-configuration automatically registers:
- `ReactiveAuthorizationFilter` (WebFlux) or `AuthorizationFilter` (WebMVC) or `AuthorizationGatewayFilter` (Gateway)
- `SecurityContextParser` for extracting security context from requests
- `SimpleAuthorization` for policy evaluation
- `LocalPolicyLoader` / `LocalPolicyInitializer` for loading local policy files
- JWT token converter and verifier (if `cosec-jwt` is on classpath)
- Request parsers for both reactive and servlet environments

## Gateway Server Reference

The `cosec-gateway-server` module is a complete reference implementation. Key configuration:

```yaml
# application.yaml
cosec:
  authentication:
    enabled: false          # Gateway relies on token injection from upstream
  jwt:
    algorithm: hmac256
    secret: FyN0Igd80Gas8stTavArGKOYnS9uLWGA_
  authorization:
    local-policy:
      enabled: true
      init-repository: true
```

Gateway-specific dependencies:
```kotlin
implementation(libs.cosec.cocache)        // Redis caching
implementation(libs.cosec.gateway)         // Gateway filter
implementation(libs.cosec.opentelemetry)   // Tracing
implementation(libs.cosec.ip2region)       // IP geolocation
implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
```

## Disabling Features

Use conditional properties to disable specific features:

```yaml
cosec:
  enabled: false              # disable everything
  authorization:
    enabled: false            # disable only authorization
  jwt:
    # not configuring jwt disables JWT auto-config
  ip2region:
    enabled: false
```

Each auto-configuration class has a corresponding `@ConditionalOn*Enabled` annotation that checks these properties.

## Troubleshooting

- **403 on all requests**: Check that policy files exist and match the `locations` pattern. Enable debug logging: `logging.level.me.ahoo.cosec.authorization.SimpleAuthorization=debug`
- **JWT token not recognized**: Verify `cosec.jwt.secret` and `cosec.jwt.algorithm` match what the token issuer uses
- **Policies not loading**: Ensure `cosec.authorization.local-policy.enabled=true` and files are in the correct classpath location
- **Root user bypasses everything**: This is by design. Root user ID defaults to `"cosec"` (configurable via `cosec.root` system property)

---
name: cosec-custom-matcher
description: "Use when extending CoSec policy matching with custom ActionMatcher or ConditionMatcher implementations, condition types, matcher factories, ServiceLoader SPI registration, or Spring bean matcher registration."
---

# CoSec Custom Matcher Development

This skill helps you create custom `ActionMatcher` and `ConditionMatcher` implementations to extend CoSec's policy evaluation logic. CoSec uses Java SPI (ServiceLoader) to discover matcher factories.

## Architecture Overview

Policy matching has two sides:
- **ActionMatcher** — determines if a request's action (path + method) matches a policy pattern
- **ConditionMatcher** — determines if contextual conditions are met (user attributes, request properties, etc.)

Both extend `RequestMatcher`. The matcher interfaces live in `cosec-api` (`me.ahoo.cosec.api.policy`); the factory interfaces live in `cosec-core` (`me.ahoo.cosec.policy.action` / `me.ahoo.cosec.policy.condition`), and factory implementations are discovered via Java SPI (ServiceLoader).

```
Policy
├── condition: ConditionMatcher (policy-level gate)
└── statements[]
    ├── Statement (effect: DENY)
    │   ├── action: ActionMatcher
    │   └── condition: ConditionMatcher
    └── Statement (effect: ALLOW)
        ├── action: ActionMatcher
        └── condition: ConditionMatcher
```

## Creating a Custom ConditionMatcher

### Step 1: Implement the ConditionMatcher

```kotlin
package com.example.cosec.condition

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.configuration.Configuration

class PremiumUserConditionMatcher(
    override val configuration: Configuration
) : ConditionMatcher {

    override val type: String = "premiumUser"

    override fun match(request: Request, securityContext: SecurityContext): Boolean {
        val isPremium = securityContext.principal.attributes["premium"]
        return isPremium == "true"
    }
}
```

Key points:
- `type` — unique string identifier used in policy JSON
- `configuration` — arbitrary key-value config passed from the policy JSON
- `match()` — return `true` if the condition is satisfied. Throwing here fails the evaluation, so prefer returning `false` for non-matches and reserve exceptions for genuine misconfiguration (the built-in rate limiter, for example, throws to signal TOO_MANY_REQUESTS)

### Step 2: Implement the Factory

```kotlin
package com.example.cosec.condition

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory

class PremiumUserConditionMatcherFactory : ConditionMatcherFactory {

    override val type: String = "premiumUser"

    override fun create(configuration: Configuration): ConditionMatcher {
        return PremiumUserConditionMatcher(configuration)
    }
}
```

### Step 3: Register via SPI

Create file: `src/main/resources/META-INF/services/me.ahoo.cosec.policy.condition.ConditionMatcherFactory`

```
com.example.cosec.condition.PremiumUserConditionMatcherFactory
```

### Step 4: Use in Policy JSON

```json
{
  "name": "PremiumEndpoints",
  "action": "/api/premium/**",
  "condition": {
    "premiumUser": {}
  }
}
```

With configuration:
```json
{
  "name": "TieredAccess",
  "action": "/api/**",
  "condition": {
    "premiumUser": {
      "minTier": "gold"
    }
  }
}
```

Access configuration in the matcher:
```kotlin
val minTier = configuration.getRequired("minTier").asString()
```

## Creating a Custom ActionMatcher

### Step 1: Implement the ActionMatcher

```kotlin
package com.example.cosec.action

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.configuration.Configuration

class HttpMethodActionMatcher(
    override val configuration: Configuration
) : ActionMatcher {

    override val type: String = "httpMethod"

    private val allowedMethods: Set<String> = configuration.getRequired("methods")
        .asString()
        .split(",")
        .map { it.trim().uppercase() }
        .toSet()

    override fun match(request: Request, securityContext: SecurityContext): Boolean {
        return request.method.uppercase() in allowedMethods
    }
}
```

### Step 2: Implement the Factory

```kotlin
package com.example.cosec.action

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.policy.action.ActionMatcherFactory

class HttpMethodActionMatcherFactory : ActionMatcherFactory {

    override val type: String = "httpMethod"

    override fun create(configuration: Configuration): ActionMatcher {
        return HttpMethodActionMatcher(configuration)
    }
}
```

### Step 3: Register via SPI

Create file: `src/main/resources/META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory`

```
com.example.cosec.action.HttpMethodActionMatcherFactory
```

### Step 4: Use in Policy JSON

```json
{
  "name": "ReadOnlyAccess",
  "action": {
    "httpMethod": {
      "methods": "GET,HEAD,OPTIONS"
    }
  }
}
```

## Accessing Configuration Values

The `Configuration` interface is a tree of typed nodes — there are no convenience accessors like `getRequiredString`; chain a `get`/`getRequired` with an `as*` conversion:

```kotlin
// Required (getRequired throws IllegalArgumentException if missing)
val value: String = configuration.getRequired("key").asString()
val count: Int = configuration.getRequired("key").asInt()

// Optional with default
val value: String = configuration.get("key")?.asString() ?: "default"

// Nested configuration (getRequired returns a Configuration directly)
val nested: Configuration = configuration.getRequired("nested")

// Collections
val list: List<String> = configuration.getRequired("tags").asStringList()
val map: Map<String, String> = configuration.getRequired("labels").asStringMap()

// Existence check
if (configuration.has("key")) { ... }
```

## Accessing Request and Context Data

### Request properties
```kotlin
request.path           // URL path
request.method         // HTTP method
request.remoteIp       // client IP
request.origin         // Origin as URI; request.origin.host for the host part
request.referer        // Referer as URI; request.referer.host for the host part
request.appId          // application ID
request.spaceId        // space ID
request.deviceId       // device ID
request.requestId      // request ID
request.getHeader("X-Custom")    // any header
request.getQuery("param")        // query parameter
request.getCookieValue("name")   // cookie value
```

### SecurityContext properties
```kotlin
securityContext.principal                    // CoSecPrincipal
securityContext.principal.id                 // user ID
securityContext.principal.authenticated      // boolean
securityContext.principal.anonymous          // boolean
securityContext.principal.roles              // Set<RoleId> (RoleCapable, RoleId = String)
securityContext.principal.policies           // Set<String> of attached policy IDs (PolicyCapable)
securityContext.principal.attributes         // Map<String, Any>
securityContext.tenant                       // Tenant (use .tenantId)
securityContext.attributes                   // MutableMap<String, Any> (carries path variables etc.)
```

## Built-in ConditionMatcher Types Reference

For reference, here are all built-in types:

| Type | Description | Key Config |
|------|-------------|------------|
| `all` | Match all (the default when `condition` is omitted) | — |
| `authenticated` | User must be logged in | — |
| `inRole` | User must have role | `value`: role name |
| `inTenant` | Must be from tenant type | `value`: `default` / `user` / `platform` |
| `eq` | Exact match | `part`, `value` |
| `contains` | Substring match | `part`, `value` |
| `startsWith` | Prefix match | `part`, `value` |
| `endsWith` | Suffix match | `part`, `value` |
| `in` | Value in list | `part`, `value`: array |
| `regular` | Regex match | `part`, `pattern`, `negate` |
| `path` | Path pattern match | `part`, `pattern`, `options` |
| `bool` | Boolean logic | `and`: array, `or`: array |
| `spel` | Spring Expression | `expression` |
| `ognl` | OGNL expression | `expression` |
| `rateLimiter` | Rate limiting | `permitsPerSecond` |
| `groupedRateLimiter` | Grouped rate limit | `part`, `permitsPerSecond`, `expireAfterAccessSecond` |

`negate: true` is accepted by **every** condition matcher above (it is handled centrally in `AbstractConditionMatcher`, not just by `regular`); `rateLimiter` is the only one without it.

Avoid redefining these type names — SPI registration and Spring registration both key off the `type` string, and a duplicate overrides the built-in factory.

## Built-in ActionMatcher Types Reference

| Type | Description | Key Config |
|------|-------------|------------|
| `path` | URL path matching | `pattern`, `method`, `options` |
| `all` | Wildcard | `method` (optional) |
| `composite` | OR combination | array of matchers |

## Spring Registration (Alternative to SPI)

You can also register matcher factories as Spring beans. The `MatcherFactoryRegister` lifecycle bean picks up every `ConditionMatcherFactory` / `ActionMatcherFactory` bean from the `ApplicationContext` at startup:

```kotlin
@Configuration
class CustomMatcherConfig {

    @Bean
    fun premiumUserConditionMatcherFactory(): ConditionMatcherFactory {
        return PremiumUserConditionMatcherFactory()
    }

    @Bean
    fun httpMethodActionMatcherFactory(): ActionMatcherFactory {
        return HttpMethodActionMatcherFactory()
    }
}
```

This approach is simpler when your matcher needs Spring dependencies (e.g., a database or external service).

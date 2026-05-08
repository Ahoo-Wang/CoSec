---
name: cosec-custom-matcher
description: "Help developers create custom ActionMatcher and ConditionMatcher implementations for CoSec. Use this skill when users need to extend CoSec's policy matching with custom logic, implement a new condition type, create a custom action matcher, or register SPI extensions."
---

# CoSec Custom Matcher Development

This skill helps you create custom `ActionMatcher` and `ConditionMatcher` implementations to extend CoSec's policy evaluation logic. CoSec uses Java SPI (ServiceLoader) to discover matcher factories.

## Architecture Overview

Policy matching has two sides:
- **ActionMatcher** — determines if a request's action (path + method) matches a policy pattern
- **ConditionMatcher** — determines if contextual conditions are met (user attributes, request properties, etc.)

Both extend `RequestMatcher` and are created by corresponding factory classes registered via SPI.

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
- `match()` — return `true` if the condition is satisfied

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
val minTier = configuration.getRequiredString("minTier")
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

    private val allowedMethods: Set<String> = configuration.getRequiredString("methods")
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

The `Configuration` interface provides typed accessors:

```kotlin
// Required (throws if missing)
val value: String = configuration.getRequiredString("key")

// Optional with default
val value: String = configuration.get("key", "default")

// Nested configuration
val nested: Configuration = configuration.getRequiredConfiguration("nested")
```

## Accessing Request and Context Data

### Request properties
```kotlin
request.path           // URL path
request.method         // HTTP method
request.remoteIp       // client IP
request.origin         // Origin header
request.referer        // Referer header
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
securityContext.principal.roles              // Set<String>
securityContext.principal.policies           // Set<String>
securityContext.principal.attributes         // Map<String, String>
securityContext.tenant                       // Tenant info
securityContext.attributes                   // MutableMap<String, Any>
```

## Built-in ConditionMatcher Types Reference

For reference, here are all built-in types:

| Type | Description | Key Config |
|------|-------------|------------|
| `authenticated` | User must be logged in | — |
| `inRole` | User must have role | `value`: role name |
| `inTenant` | Must be from tenant | `value`: tenant ID |
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
| `groupedRateLimiter` | Grouped rate limit | `permitsPerSecond`, `groupKey` |

## Built-in ActionMatcher Types Reference

| Type | Description | Key Config |
|------|-------------|------------|
| `path` | URL path matching | `pattern`, `method`, `options` |
| `all` | Wildcard | `method` (optional) |
| `composite` | OR combination | array of matchers |

## Spring Registration (Alternative to SPI)

You can also register matcher factories as Spring beans. The `MatcherFactoryRegister` auto-configuration picks them up from the `ApplicationContext`:

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

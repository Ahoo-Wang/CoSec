---
title: Custom Matchers (SPI)
description: How to extend CoSec with custom ActionMatcher and ConditionMatcher implementations using the Java Service Provider Interface.
---

# Custom Matchers (SPI)

CoSec's policy system is fully extensible through two SPI (Service Provider Interface) extension points: `ActionMatcherFactory` for defining how requests match actions, and `ConditionMatcherFactory` for defining additional conditions on policy statements. Custom matchers are discovered automatically via Java's `ServiceLoader` and Spring's `ApplicationContext`.

## Extension Architecture

```mermaid
graph TD
    subgraph "SPI Discovery"
        A["Java ServiceLoader<br>(META-INF/services)"]
        B["Spring ApplicationContext<br>(bean discovery)"]
    end
    subgraph "Provider Registry"
        C["ActionMatcherFactoryProvider<br>(ConcurrentHashMap)"]
        D["ConditionMatcherFactoryProvider<br>(ConcurrentHashMap)"]
    end
    subgraph "Runtime"
        E["Policy Deserialization"]
        E --> F["ActionMatcherFactory.create(config)"]
        E --> G["ConditionMatcherFactory.create(config)"]
    end
    A --> C
    B --> C
    B --> D
    A --> D
    C --> F
    D --> G

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## ActionMatcherFactory

Factory interface for creating `ActionMatcher` instances. Each factory is identified by a unique `type` string that is used in policy JSON to reference the matcher.

```kotlin
interface ActionMatcherFactory {
    val type: String
    fun create(configuration: Configuration): ActionMatcher
}
```

### Built-in Action Matchers

| Factory Class | Type | Description |
|--------------|------|-------------|
| `AllActionMatcherFactory` | `all` | Matches all actions unconditionally |
| `PathActionMatcherFactory` | `path` | Matches by URL path pattern and HTTP method |
| `CompositeActionMatcherFactory` | `composite` | Combines multiple matchers with AND/OR logic |

## ConditionMatcherFactory

Factory interface for creating `ConditionMatcher` instances. Condition matchers add additional constraints beyond action matching.

```kotlin
interface ConditionMatcherFactory {
    val type: String
    fun create(configuration: Configuration): ConditionMatcher
}
```

### Built-in Condition Matchers

| Category | Matchers | Description |
|----------|----------|-------------|
| Path-based | `Eq`, `Contains`, `StartsWith`, `EndsWith`, `In`, `Regular` | Match request properties against values |
| Context-based | `Authenticated`, `InRole`, `InTenant` | Match security context properties |
| Rate limiting | Rate limiter matchers | Enforce request rate limits |
| Expression | `OGNL`, `SpEL` | Evaluate custom expressions |

## Registration Flow

### Step 1: Java ServiceLoader (META-INF/services)

For non-Spring contexts, factories are discovered via `ServiceLoader` at class loading time.

**File**: `META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory`

```
me.ahoo.cosec.policy.action.AllActionMatcherFactory
me.ahoo.cosec.policy.action.PathActionMatcherFactory
me.ahoo.cosec.policy.action.CompositeActionMatcherFactory
```

**File**: `META-INF/services/me.ahoo.cosec.policy.condition.ConditionMatcherFactory`

```
me.ahoo.cosec.policy.condition.AllConditionMatcherFactory
me.ahoo.cosec.policy.condition.authenticated.AuthenticatedConditionMatcherFactory
me.ahoo.cosec.policy.condition.eq.EqConditionMatcherFactory
...
```

### Step 2: Provider Registry

The `ActionMatcherFactoryProvider` and `ConditionMatcherFactoryProvider` singletons maintain a `ConcurrentHashMap` of all registered factories.

```mermaid
sequenceDiagram
    autonumber
    participant SL as ServiceLoader
    participant Provider as ActionMatcherFactoryProvider
    participant Map as ConcurrentHashMap

    Note over Provider: Static init block
    Provider->>SL: load(ActionMatcherFactory::class.java)
    SL-->>Provider: Iterator of factories
    loop For each factory
        Provider->>Map: put(factory.type, factory)
    end
    Note over Provider: Factories now available via get() / getRequired()



```

### Step 3: Spring SmartLifecycle (MatcherFactoryRegister)

When running in a Spring context, `MatcherFactoryRegister` implements `SmartLifecycle` to register all Spring-managed factory beans with the provider singletons. This runs at startup and ensures that custom factories defined as `@Bean` are available for policy evaluation.

```kotlin
class MatcherFactoryRegister(
    private val applicationContext: ApplicationContext
) : SmartLifecycle {
    override fun start() {
        applicationContext.getBeansOfType<ConditionMatcherFactory>().values.forEach {
            ConditionMatcherFactoryProvider.register(it)
        }
        applicationContext.getBeansOfType<ActionMatcherFactory>().values.forEach {
            ActionMatcherFactoryProvider.register(it)
        }
    }
}
```

```mermaid
graph TD
    A["Spring ApplicationContext"] --> B["MatcherFactoryRegister<br>(SmartLifecycle.start())"]
    B --> C["Get all ActionMatcherFactory beans"]
    B --> D["Get all ConditionMatcherFactory beans"]
    C --> E["ActionMatcherFactoryProvider.register()"]
    D --> F["ConditionMatcherFactoryProvider.register()"]

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Creating a Custom Action Matcher

### Step 1: Implement the Matcher

```kotlin
class HttpMethodActionMatcher(private val method: String) : ActionMatcher {
    override fun match(request: Request): Boolean {
        return request.method.equals(method, ignoreCase = true)
    }
}
```

### Step 2: Implement the Factory

```kotlin
class HttpMethodActionMatcherFactory : ActionMatcherFactory {
    override val type = "httpMethod"
    override fun create(configuration: Configuration): ActionMatcher {
        val method = configuration.getConfigValue("method", String::class.java)
        return HttpMethodActionMatcher(method)
    }
}
```

### Step 3: Register via META-INF/services

**File**: `META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory`

```
com.example.HttpMethodActionMatcherFactory
```

### Step 4: Use in Policy JSON

```json
{
  "effect": "ALLOW",
  "action": {
    "type": "httpMethod",
    "method": "GET"
  }
}
```

## Creating a Custom Condition Matcher

The same pattern applies for `ConditionMatcherFactory`. Implement the `ConditionMatcher` interface, create a factory, and register it.

## References

- [cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/ActionMatcherFactory.kt:30](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/ActionMatcherFactory.kt#L30) -- ActionMatcherFactory interface
- [cosec-core/src/main/kotlin/me/ahoo/cosec/policy/condition/ConditionMatcherFactory.kt:30](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/policy/condition/ConditionMatcherFactory.kt#L30) -- ConditionMatcherFactory interface
- [cosec-spring-boot-starter/src/main/kotlin/.../MatcherFactoryRegister.kt:24](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/policy/MatcherFactoryRegister.kt#L24) -- Spring SmartLifecycle registration
- [cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/ActionMatcherFactoryProvider.kt:20](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/ActionMatcherFactoryProvider.kt#L20) -- Provider singleton
- [cosec-core/src/main/kotlin/me/ahoo/cosec/policy/condition/ConditionMatcherFactoryProvider.kt:20](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/policy/condition/ConditionMatcherFactoryProvider.kt#L20) -- Provider singleton
- [cosec-core/src/main/resources/META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/resources/META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory) -- Built-in service registrations

## Related Pages

- [Auto-Configuration](./auto-configuration.md)
- [OpenAPI Integration](../integrations/openapi.md)
- [IP Geolocation](../integrations/ip-geolocation.md)
- [Testing](../operations/testing.md)

---
title: Action Matchers
description: The ActionMatcher SPI including PathActionMatcher, AllActionMatcher, CompositeActionMatcher, the factory/provider pattern, and ReplaceablePathActionMatcher for dynamic path patterns.
---

# Action Matchers

Action matchers determine whether an incoming request matches the action pattern defined in a policy statement. CoSec provides three built-in matcher types and an SPI (Service Provider Interface) for custom implementations. All action matchers are resolved at policy load time via the `ActionMatcherFactory` SPI.

## ActionMatcher Interface

[ActionMatcher](cosec-api/src/main/kotlin/me/ahoo/cosec/api/policy/ActionMatcher.kt) extends `RequestMatcher`:

```kotlin
interface ActionMatcher : RequestMatcher
```

The `RequestMatcher` interface defines:

```kotlin
fun match(request: Request, securityContext: SecurityContext): Boolean
```

## Built-In Action Matchers

### PathActionMatcher

[PathActionMatcher](cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/PathActionMatcher.kt) uses Spring's `PathPattern` for URL pattern matching with path variable extraction:

```kotlin
class PathActionMatcher(
    private val patternParser: PathPatternParser,
    private val pathPattern: PathPattern,
    configuration: Configuration
) : AbstractActionMatcher(PathActionMatcherFactory.TYPE, configuration)
```

When a match succeeds, extracted path variables are automatically stored in the `SecurityContext`:

```kotlin
val pathMatchInfo = pathPattern.matchAndExtract(pathContainer) ?: return false
securityContext.setPathVariables(pathMatchInfo.uriVariables)
```

This makes path variables available to condition matchers via the `request.path.var.xxx` part extractor.

#### AbstractActionMatcher

[AbstractActionMatcher](cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/AbstractActionMatcher.kt) adds HTTP method filtering:

```kotlin
abstract class AbstractActionMatcher(
    override val type: String,
    final override val configuration: Configuration
) : ActionMatcher {
    val method: Set<String> = configuration.asMethod()
    override fun match(request, securityContext): Boolean {
        if (method.isNotEmpty() && !method.contains(request.method.uppercase())) return false
        return internalMatch(request, securityContext)
    }
}
```

The `method` configuration key accepts a single method (`"GET"`) or a list (`["GET", "POST"]`).

### AllActionMatcher

[AllActionMatcher](cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/AllActionMatcher.kt) matches every request:

```kotlin
override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean = true
```

Triggered by the wildcard `"*"` pattern.

### CompositeActionMatcher

[CompositeActionMatcher](cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/CompositeActionMatcher.kt) combines multiple matchers using OR logic:

```kotlin
override fun match(request: Request, securityContext: SecurityContext): Boolean =
    actionMatchers.any { it.match(request, securityContext) }
```

Created automatically when a policy action defines multiple path patterns.

### ReplaceablePathActionMatcher

[ReplaceablePathActionMatcher](cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/PathActionMatcher.kt) supports SpEL template expressions in path patterns:

```kotlin
class ReplaceablePathActionMatcher(
    private val patternParser: PathPatternParser,
    private val pattern: String,
    configuration: Configuration
) : AbstractActionMatcher(PathActionMatcherFactory.TYPE, configuration)
```

When the pattern contains SpEL templates (e.g., `"#{context.principal.attributes.customPath}"`), the pattern is resolved at runtime from the security context, enabling dynamic path patterns per user or tenant.

## SPI: ActionMatcherFactory

[ActionMatcherFactory](cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/ActionMatcherFactory.kt) is the SPI interface for creating matchers:

```kotlin
interface ActionMatcherFactory {
    val type: String
    fun create(configuration: Configuration): ActionMatcher
}
```

### ActionMatcherFactoryProvider

[ActionMatcherFactoryProvider](cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/ActionMatcherFactoryProvider.kt) discovers factories via Java SPI (`ServiceLoader`):

```kotlin
object ActionMatcherFactoryProvider {
    init {
        ServiceLoader.load(ActionMatcherFactory::class.java)
            .forEach { register(it) }
    }
}
```

Built-in factories are registered in `META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory`:

| Factory | Type | Matcher |
|---------|------|---------|
| `PathActionMatcherFactory` | `"path"` | URL pattern matching |
| `AllActionMatcherFactory` | `"all"` | Wildcard matching |
| `CompositeActionMatcherFactory` | `"composite"` | OR combination |

### Registering Custom Matchers

1. Implement `ActionMatcherFactory` with a unique `type` string
2. Create the file `META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory`
3. Add the fully qualified class name of your factory

## Architecture Diagrams

### Action Matcher Class Hierarchy

```mermaid
classDiagram
    direction TB
    class RequestMatcher {
        <<interface>>
        +match(request, context): Boolean
    }
    class ActionMatcher {
        <<abstract>>
        +type: String
        +configuration: Configuration
        +method: Set~String~
        +match(request, context): Boolean
        #internalMatch(request, context): Boolean
    }
    class PathActionMatcher {
        -patternParser: PathPatternParser
        -pathPattern: PathPattern
        #internalMatch(request, context): Boolean
    }
    class ReplaceablePathActionMatcher {
        -pattern: String
        -expression: Expression
        #internalMatch(request, context): Boolean
    }
    class AllActionMatcher {
        +INSTANCE: AllActionMatcher
        #internalMatch(): Boolean = true
    }
    class CompositeActionMatcher {
        -actionMatchers: List~ActionMatcher~
        +match(request, context): Boolean
    }
    class ActionMatcherFactory {
        <<interface>>
        +type: String
        +create(config): ActionMatcher
    }
    class PathActionMatcherFactory {
        +type = path
    }
    class AllActionMatcherFactory {
        +type = all
    }
    class CompositeActionMatcherFactory {
        +type = composite
    }
    class ActionMatcherFactoryProvider {
        <<object>>
        -factories: ConcurrentHashMap
        +register(factory)
        +getRequired(type): ActionMatcherFactory
    }

    RequestMatcher <|-- ActionMatcher
    ActionMatcher <|.. AbstractActionMatcher
    ActionMatcher <|.. CompositeActionMatcher
    AbstractActionMatcher <|-- PathActionMatcher
    AbstractActionMatcher <|-- ReplaceablePathActionMatcher
    AbstractActionMatcher <|-- AllActionMatcher
    ActionMatcherFactory <|.. PathActionMatcherFactory
    ActionMatcherFactory <|.. AllActionMatcherFactory
    ActionMatcherFactory <|.. CompositeActionMatcherFactory
    ActionMatcherFactoryProvider --> ActionMatcherFactory : manages
    PathActionMatcherFactory ..> PathActionMatcher : creates
    AllActionMatcherFactory ..> AllActionMatcher : creates
    CompositeActionMatcherFactory ..> CompositeActionMatcher : creates



```

### Factory SPI Resolution Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Policy as PolicyLoader
    participant Config as Configuration
    participant Provider as ActionMatcherFactoryProvider
    participant Factory as ActionMatcherFactory
    participant Matcher as ActionMatcher

    Policy->>Config: get action configuration
    Policy->>Provider: getRequired("path")
    Provider-->>Factory: PathActionMatcherFactory
    Policy->>Factory: create(configuration)
    Factory->>Factory: parse PathPattern
    Factory-->>Matcher: PathActionMatcher
    Matcher-->>Policy: ready for use

```

### Path Matching with Variable Extraction

```mermaid
flowchart TD
    A["PathActionMatcher.match(request, context)"] --> B{"method filter"}
    B -->|"method not matched"| C["return false"]
    B -->|"method matched or empty"| D["parsePath(request.path)"]
    D --> E["pathPattern.matchAndExtract(pathContainer)"]
    E --> F{"match found?"}
    F -->|"no"| G["return false"]
    F -->|"yes"| H["setPathVariables(uriVariables)"]
    H --> I["return true"]

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style H fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style I fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Policy JSON Examples

### Single path pattern

```json
{
  "action": {
    "path": {
      "pattern": "/api/users/**",
      "method": ["GET", "POST"]
    }
  }
}
```

### Multiple path patterns (CompositeActionMatcher)

```json
{
  "action": {
    "path": {
      "pattern": ["/api/users/**", "/api/admin/**"]
    }
  }
}
```

### Wildcard (AllActionMatcher)

```json
{
  "action": "*"
}
```

## References

- [PathActionMatcher.kt:42](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/PathActionMatcher.kt#L42) - Path-based action matching with variable extraction
- [AllActionMatcher.kt:30](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/AllActionMatcher.kt#L30) - Wildcard matcher
- [CompositeActionMatcher.kt:32](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/CompositeActionMatcher.kt#L32) - OR-combined matcher
- [ActionMatcherFactory.kt:30](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/ActionMatcherFactory.kt#L30) - Factory SPI interface
- [ActionMatcherFactoryProvider.kt:20](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/ActionMatcherFactoryProvider.kt#L20) - SPI provider with ServiceLoader

## Related Pages

- [Policy Evaluation](./policy-evaluation.md) - How action matchers are used in statement verification
- [Condition Matchers](./condition-matchers.md) - Condition evaluation after action matching
- [Authorization Flow](./authorization-flow.md) - Full authorization pipeline
- [Permissions and Roles](./permissions-roles.md) - Permission-level action matching

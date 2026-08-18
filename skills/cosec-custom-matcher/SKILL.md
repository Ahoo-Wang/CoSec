---
name: cosec-custom-matcher
description: Extend CoSec with custom ActionMatcher or ConditionMatcher implementations and register their factories through ServiceLoader or Spring. Do not use for policies that built-in matchers already express.
---

# CoSec Custom Matchers

Add a matcher only after confirming that the built-ins cannot express the rule. Prefer composing `path`, `bool`, part matchers, or the local/Redis rate limiters over new code.

## Extension contract

- `ActionMatcher` and `ConditionMatcher` live in `cosec-api` and extend `RequestMatcher`.
- Their factory interfaces and providers live in `cosec-core` under `me.ahoo.cosec.policy.action` and `me.ahoo.cosec.policy.condition`.
- A factory's `type` is the object key used in policy JSON. It must not collide with another factory; later registration replaces the existing entry.
- Do not change the SPI interfaces to add a matcher. Implement them and register the factory.
- Keep matching synchronous and side-effect free unless the matcher intentionally enforces a stateful control such as rate limiting.

Before editing, inspect the target CoSec version's interfaces and the nearest built-in matcher. In a CoSec checkout, the authoritative files are:

- `cosec-api/src/main/kotlin/me/ahoo/cosec/api/principal/RequestMatcher.kt`
- `cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/`
- `cosec-core/src/main/kotlin/me/ahoo/cosec/policy/condition/`

## Minimal condition matcher

Use `AbstractConditionMatcher` when `negate` support is desirable. Parse and validate configuration once during construction, not on every request.

```kotlin
class PremiumUserConditionMatcher(configuration: Configuration) :
    AbstractConditionMatcher(PremiumUserConditionMatcherFactory.TYPE, configuration) {

    private val requiredTier = configuration.getRequired("tier").asString()

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        val tier = securityContext.principal.attributes["tier"]?.toString()
        return tier == requiredTier
    }
}

class PremiumUserConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "premiumUser"
    }

    override val type: String = TYPE

    override fun create(configuration: Configuration): ConditionMatcher =
        PremiumUserConditionMatcher(configuration)
}
```

Register it for non-Spring and Spring environments with:

```text
# src/main/resources/META-INF/services/me.ahoo.cosec.policy.condition.ConditionMatcherFactory
com.example.cosec.PremiumUserConditionMatcherFactory
```

```json
{
  "action": "/api/premium/**",
  "condition": {
    "premiumUser": { "tier": "gold" }
  }
}
```

For an action matcher, use the same pattern with `AbstractActionMatcher`, `ActionMatcherFactory`, and:

```text
src/main/resources/META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory
```

## Spring registration

When a factory needs Spring-managed dependencies, expose it as a bean instead of adding a service file:

```kotlin
@Bean
fun premiumUserConditionMatcherFactory(): ConditionMatcherFactory =
    PremiumUserConditionMatcherFactory()
```

`MatcherFactoryRegister` registers all action and condition factory beans at startup. Do not register the same type through both mechanisms unless replacement is intentional.

## Useful API surface

Read configuration through `get`/`getRequired` followed by `asString`, `asBoolean`, `asInt`, `asLong`, `asDouble`, `asList`, or `asMap`.

Request matching commonly uses `path`, `method`, `remoteIp`, `origin`, `referer`, `appId`, `spaceId`, `deviceId`, `requestId`, headers, queries, cookies, and request attributes. Context matching commonly uses principal ID/authentication/roles/policies/attributes, tenant ID, and context attributes. Inspect the target interfaces rather than assuming fields from another CoSec version.

## Existing type names

Do not replace these unless the user explicitly wants an override:

- Actions: `all`, `path`, `composite`
- Conditions: `all`, `authenticated`, `inRole`, `inTenant`, `eq`, `contains`, `startsWith`, `endsWith`, `in`, `regular`, `path`, `bool`, `spel`, `ognl`, `rateLimiter`, `groupedRateLimiter`
- With Redis cache support: `redisRateLimiter`, `redisGroupedRateLimiter`

## Verification

Add one focused test that creates the matcher through its factory and checks a match and non-match. If registration changed, also deserialize one policy using the custom type so a missing service entry or Spring bean fails the test. Run the narrow module test and the repository's static analysis command.

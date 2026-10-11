# Extending

CoSec is extended by providing beans (with the starter) or implementations of its interfaces. Starter beans
are `@ConditionalOnMissingBean`, so your own bean of the same type replaces the default.

## Custom conditions and actions

The policy language is extensible. A matcher type is a **factory** registered under a type name; policies
refer to it as `{ "<type>": { ...configuration } }`.

```kotlin
// "businessHours": matches Monday–Friday between `from` and `to` (hours) in `zone`.
class BusinessHoursConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "businessHours"
    }

    override val type: String = TYPE

    override fun create(configuration: Configuration): ConditionMatcher =
        BusinessHoursConditionMatcher(configuration)
}

class BusinessHoursConditionMatcher(configuration: Configuration) :
    AbstractConditionMatcher(BusinessHoursConditionMatcherFactory.TYPE, configuration) {
    private val zone = ZoneId.of(configuration.getRequired("zone").asString())
    private val from = configuration.getRequired("from").asInt()
    private val to = configuration.getRequired("to").asInt()

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        val now = ZonedDateTime.now(zone)
        return now.dayOfWeek < DayOfWeek.SATURDAY && now.hour in from until to
    }
}
```

```json
"condition": { "businessHours": { "zone": "Asia/Shanghai", "from": 9, "to": 18 } }
```

- Extend `AbstractConditionMatcher` (it handles `negate`) or `PartConditionMatcher` (it also extracts a
  [`part`](../guide/policies.md#value-conditions)). For actions, extend `AbstractActionMatcher` (it handles
  `method`) and implement `ActionMatcherFactory`.
- **Register** the factory either as a Spring bean (the starter registers `ActionMatcherFactory` /
  `ConditionMatcherFactory` beans before local policies are loaded), or through `ServiceLoader` in
  `META-INF/services/me.ahoo.cosec.policy.condition.ConditionMatcherFactory` (or `…action.ActionMatcherFactory`).
  A factory registered under an existing type replaces the built-in one.
- **Keep matchers fast and free of I/O.** They run for every request, possibly on an event loop. Read
  configuration once, in the constructor. Throwing denies the request.

## Authorization hooks

| Extension point | Use it to |
|---|---|
| `BlacklistChecker` | Deny requests before any policy is evaluated (`check(request, context): Mono<Boolean>`; `false` = blocked). |
| `RootPrincipalMatcher` | Decide which principals bypass authorization, for example by a signed claim instead of an id. |
| `PolicyRepository`, `AppRolePermissionRepository` | Keep policies and role grants somewhere other than Redis. Return `Mono`s and keep blocking I/O off the caller's thread. |
| `Authorization` | Wrap or replace the decision. Decorators should implement `DecisionAuthorization` and forward `decide(...)`, so tracing and auditing still see what matched. |

## Request and security context

| Extension point | Use it to |
|---|---|
| `RequestAttributesAppender` | Enrich each request with attributes that conditions can read as `request.attributes.<name>` (`append(request): Request`). Return a new request with `request.mergeAttributes(...)`. |
| `SecurityContextParser` | Resolve the principal from something other than a CoSec JWT. Throw `TokenVerificationException` for an invalid credential; the caller is then treated as anonymous, and a denial reports the token failure. |
| `TokenStore` | Store revoked token ids somewhere other than Redis. |

## Authentication

| Extension point | Use it to |
|---|---|
| `Authentication<C, CoSecPrincipal>` | Add a login method for credentials of type `C`. It is registered automatically. |
| `AbstractSwitchTenantAuthentication` | Let principals switch tenants. |
| `SocialUserPrincipalConverter` | Map a social login's user to your principal (`convert(provider, socialUser): Mono<CoSecPrincipal>`). |

## Auditing

Implement `AuditEventSink` (`publish(event)`) to send audit events to your own system. It replaces the
logging sink and the Kafka sink. Publishing runs on the request path: hand events off asynchronously rather
than blocking. Exceptions are logged and never affect the decision.

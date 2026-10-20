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
- **Mark blocking matchers.** If a condition matcher must block (a network or database call), implement
  `me.ahoo.cosec.policy.BlockingAware` with `requiresBlocking = true`. Authorization then evaluates the layers that use it on `boundedElastic`. A composite matcher that wraps
  other matchers should report `true` when any child does, as `bool` does.
- **Skip side effects during validation and explanation.** Saving a policy validates it by running every
  condition once against a mock request, and [explaining a decision](../guide/troubleshooting.md#explain-a-decision)
  runs every condition too. A custom matcher with an unavoidable external side effect must check `securityContext.isPolicyEvaluation` (`me.ahoo.cosec.policy`) and return
  without the side effect when it is `true`. The marker can't be forged from policy or request data.
- **Path variables are scoped to their statement.** A matcher that reads path variables in code
  (`securityContext.getPathVariables()`) is not caught by the load-time check, which only inspects configuration
  ([Path variables](../guide/policies.md#actions)). The variables are cleared before every statement matches its
  action, so such a matcher sees only those captured by its own statement's `path` action, and an empty map in a
  policy or app permission condition, under any other action, or inside an action matcher itself.

## Authorization hooks

| Extension point | Use it to |
|---|---|
| `BlacklistChecker` | Deny requests before any policy is evaluated (`check(request, context): Mono<Boolean>`; `false` = blocked). |
| `RootPrincipalMatcher` | Decide which principals bypass authorization, for example by a signed claim instead of an id. |
| `PolicyRepository`, `AppRolePermissionRepository` | Keep policies and role grants somewhere other than Redis. Return `Mono`s and keep blocking I/O off the caller's thread. Return only the grants made for the given app and space: the role ids passed in are already the principal's roles in that space. |
| `ShadowPolicyProvider`, `ShadowEventSink` | Supply [shadow](../guide/policies.md#trying-changes-in-shadow-mode) candidate policies from somewhere other than files, and send the diffs somewhere other than the log. |
| `RateLimitRuleProvider` | Load [rate limit rules](../guide/rate-limiting.md#rules) from somewhere other than JSON files. Called on every allowed request: answer from memory. |
| `CoSecAuthorizationCustomizer` | Wrap the starter's authorization (outside rate limiting, inside auditing and tracing), as the metrics do. |
| `Authorization` | Wrap or replace the decision. Decorators should implement `DecisionAuthorization` and forward `decide(...)`, so tracing and auditing still see what matched. |

## Rate limiter backends

A `RateLimiter` bean replaces the counter store (Redis with `cache-support`, otherwise in memory).
`tryAcquire(ruleId, bucket, rateLimit): Mono<RateLimitResult>` spends one permit from the bucket and reports
the permits left. `RateLimit.resultOf(...)` builds the result from a sliding-window estimate. Keep blocking
I/O on `boundedElastic`, and signal failures as errors: the rule's `strictFailure` decides whether they reject
the request.

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

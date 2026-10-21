---
title: "Extending CoSec"
description: "Add your own conditions, actions, repositories, rate limiter backends and hooks."
---

# Extending CoSec

For developers who need behavior CoSec does not ship. This page lists every extension point, what it must
guarantee (purity, no event-loop blocking, fail closed) and how to register it. The guarantees come from the
[architecture invariants](./overview.md#invariants); an extension that breaks one weakens the whole decision.

## Extension points

With the starter, you extend CoSec by declaring a bean. The starter's beans are `@ConditionalOnMissingBean`, so a
bean of the same type replaces the default. Without Spring, pass your implementation to the constructor of the
class that uses it (for example `SimpleAuthorization`).

| Extension point | Module | Replace it to |
|---|---|---|
| `ConditionMatcherFactory`, `ActionMatcherFactory` | `cosec-core` | Add a condition or action type to the policy language. |
| `PolicyRepository`, `AppRolePermissionRepository` | `cosec-core` | Keep policies and role grants somewhere other than Redis. |
| `BlacklistChecker` | `cosec-core` | Deny requests before any policy is evaluated. |
| `RootPrincipalMatcher` | `cosec-core` | Decide which principals bypass authorization. |
| `RateLimitRuleProvider`, `RateLimiter` | `cosec-core` | Load rate limit rules from elsewhere; count permits somewhere other than Redis or memory. |
| `ShadowPolicyProvider`, `ShadowEventSink` | `cosec-core` | Supply shadow candidates from elsewhere; send shadow diffs somewhere other than the log. |
| `CoSecAuthorizationCustomizer`, `Authorization` | starter, `cosec-api` | Wrap or replace the decision. |
| `RequestAttributesAppender` | `cosec-core` | Add request attributes that conditions can read. |
| `SecurityContextParser`, `TokenStore`, `ApiKeyStore` | `cosec-core` | Resolve the principal differently; store revocations or API keys elsewhere. |
| `Authentication`, `AbstractSwitchTenantAuthentication`, `SocialUserPrincipalConverter` | `cosec-api`, `cosec-core`, `cosec-social` | Add login methods, tenant switching, social user mapping. |
| `AuditEventSink` | `cosec-api` | Send audit events to your own system. |

Changing one of these interfaces (rather than implementing it) is a breaking change for every application that
implements it.

There is no separate decision listener. To observe decisions, wrap the authorization (see
[Authorization hooks](#authorization-hooks)) or implement an `AuditEventSink`. A listener API is proposed in
[RFC 0001](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0001-target-architecture.md) and does not exist yet.

## Custom conditions and actions

Add a matcher only when the built-in actions and conditions cannot express the rule; composing `path`, `bool` and
the part conditions covers most cases (see the [policy language](../policies/language.md)). A matcher type is a
**factory** registered under a type name, and policies refer to it as `{ "<type>": { ...configuration } }`:

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

**Base classes.** Extend `AbstractConditionMatcher` (it handles `negate`) or `PartConditionMatcher` (it also extracts
a request or context `part`). For actions, extend `AbstractActionMatcher` (it handles `method`) and implement
`ActionMatcherFactory`. The interfaces are in `me.ahoo.cosec.policy.condition` and `me.ahoo.cosec.policy.action`.

### Registering a factory

There are two ways, and both end in the same registry (`ConditionMatcherFactoryProvider`,
`ActionMatcherFactoryProvider`):

- **`ServiceLoader`**, for any environment. List the factory class in
  `src/main/resources/META-INF/services/me.ahoo.cosec.policy.condition.ConditionMatcherFactory` (or
  `…/me.ahoo.cosec.policy.action.ActionMatcherFactory`). The registry loads these entries when it is first used.
- **A Spring bean**, when the factory needs Spring-managed dependencies. The starter's `MatcherFactoryRegister`
  registers every `ConditionMatcherFactory` and `ActionMatcherFactory` bean at startup, before local policies are
  loaded into the repository.

The last registration of a type wins. A Spring bean is registered after the `ServiceLoader` entries, so it reliably
replaces a built-in type; between two `ServiceLoader` entries for the same type, classpath order decides. Do not
register one type both ways unless you mean to replace it.

### Rules for matchers

- **Do no I/O and keep no state.** Matchers run for every request, including denied ones, possibly on an event loop.
  Read configuration once, in the constructor. A quota is a [rate limit rule](../policies/rate-limiting.md), not
  a matcher. Throwing denies the request.
- **Mark blocking matchers.** If a condition matcher must block (a network or database call), implement
  `me.ahoo.cosec.policy.BlockingAware` with `requiresBlocking = true`. `SimpleAuthorization` then evaluates the
  layer containing it on `boundedElastic`; other layers stay on the caller's thread. A composite matcher must report
  `true` when any child does, as `bool` does. Action matchers are never inspected: they must not block.
- **Skip side effects during evaluation-only runs.** Saving a policy validates it by running every condition once
  against a mock request, and explaining a decision runs conditions too. A matcher with an unavoidable external side
  effect checks `securityContext.isPolicyEvaluation` (`me.ahoo.cosec.policy`) and skips it when `true`. Policy and
  request data cannot set this marker.
- **Path variables are scoped to their statement.** A matcher that reads path variables in code
  (`securityContext.getPathVariables()`) is invisible to the load-time scope check, which only inspects
  configuration strings. The variables are cleared before every statement matches its action, so such a matcher sees
  only the variables captured by its own statement's `path` action. In a policy-level or app-permission condition,
  under any other action, and inside an action matcher, it sees none.

Add a test that creates the matcher through its factory and checks a match and a non-match. If you changed
registration, also deserialize one policy using the new type, so a missing service entry or bean fails the test.

## Authorization hooks

| Extension point | Contract |
|---|---|
| `BlacklistChecker` | `check(request, context): Mono<Boolean>`, `false` = blocked. Runs first, for every principal including root. |
| `RootPrincipalMatcher` | `isRoot(principal): Boolean`. A root principal bypasses every policy and permission, but not the blacklist. The starter uses `RootPrincipalMatcher.NONE` unless `cosec.authorization.root-enabled=true`, then `DEFAULT` ([Root principal](../guide/security-model.md#root-principal)). Provide your own to require a stronger signal, such as a signed claim. |
| `CoSecAuthorizationCustomizer` | `customize(authorization): Authorization`. Wraps the starter's authorization outside rate limiting and inside auditing and tracing; customizers apply in `@Order` order. Metrics are added this way. |
| `Authorization` | Replaces the whole chain. A replacement gets no auditing, rate limiting or shadow mode (tracing still wraps it); compose `AuditingAuthorization`, `RateLimitingAuthorization` and `ShadowAuthorization` yourself. |

A decorator should implement `DecisionAuthorization` and forward `decide(...)` rather than only `authorize(...)`, so
the matched `VerifyContext` still reaches auditing, tracing and explanation. Where each decorator sits is described
in the [authorization chain](./overview.md#authorization-chain).

## Repositories

`PolicyRepository` and `AppRolePermissionRepository` are what authorization reads. `cosec-cocache` implements them
on Redis; with any other store, you implement them.

```kotlin
interface PolicyRepository {
    fun getGlobalPolicy(): Mono<List<Policy>>
    fun getPolicies(policyIds: Set<String>): Mono<List<Policy>>
    fun setPolicy(policy: Policy): Mono<Void>
}

interface AppRolePermissionRepository {
    fun getAppRolePermission(appId: AppId, spaceId: SpaceId, roleIds: Set<RoleId>): Mono<AppRolePermission>
}
```

An implementation must keep these promises:

- **`getPolicies` returns only the policies it found.** Do not invent placeholders and do not fail for ids you do not
  have: `SimpleAuthorization` compares the result with the requested ids and fails the request with
  `MissingPolicyException` when one is missing. `LocalPolicyInitializer` relies on the same contract to decide which
  local policies still need to be written.
- **`getGlobalPolicy` must not silently drop policies.** Return every policy of type `global`. If your store keeps an
  index separately from the content, fail when an indexed policy has no content: dropping it could drop a global
  `DENY` and widen access.
- **Failures are errors.** Signal a failed read as an error, never as an empty result. An error denies the request
  (`500`); an empty result would be read as "no policy", which can allow.
- **`getAppRolePermission` returns only the grants made in `appId` for `spaceId`.** The `roleIds` passed in are already
  the principal's roles that apply in that space, with their `@space` suffix removed. Never return grants made in
  another app or space. Return an empty `Mono` when the app has no app permission.
- **Do not block the subscriber.** Return a `Mono` that does its I/O without blocking, or move blocking I/O to
  `Schedulers.boundedElastic()`. Answer from memory without a thread hop when you can: authorization reads the
  repositories on every request.
- **Writes must not open a window that widens access.** If a write touches several keys (content and an index),
  order them so that a reader in between sees at most the old or the new state, never a policy applied where it
  does not belong.

Storage choices and operating Redis are covered in [Storage](../operations/storage.md).

## Rate limiter backends

| Extension point | Contract |
|---|---|
| `RateLimitRuleProvider` | `getRules(): List<RateLimitRule>`. Called on every allowed request: answer from memory. `RateLimitRuleProvider.of(rules)` rejects duplicate ids. |
| `RateLimiter` | `tryAcquire(ruleId, bucket, rateLimit): Mono<RateLimitResult>` spends one permit from the bucket and reports the permits left. `RateLimit.resultOf(...)` builds the result from a sliding-window estimate. |

A `RateLimiter` must not block the subscribing thread, which is a Netty event loop under WebFlux and Gateway: run
blocking I/O on `boundedElastic`. Signal a failure as an error; the rule's `strictFailure` decides whether it
rejects the request. Rules, keys and failure behavior for users are in [Rate limiting](../policies/rate-limiting.md).

## Shadow mode

| Extension point | Contract |
|---|---|
| `ShadowPolicyProvider` | `getPolicies()` and `getAppPermissions()` return candidates that replace the enforced item with the same id, or add new ones. Called for every shadowed request: answer from memory, and return the same list instance so the overlay is reused. Fail loudly on a candidate you cannot load: a candidate left out silently makes the run report "no difference". |
| `ShadowEventSink` | `publish(diff)` receives each difference. Runs on the request path: hand it off rather than block. |

How to use shadow mode is in [Testing policies](../policies/testing.md#trying-changes-in-shadow-mode).

## Request and security context

| Extension point | Contract |
|---|---|
| `RequestAttributesAppender` | `append(request): Request`. Enrich each request with attributes that conditions read as `request.attributes.<name>`; return a new request with `request.mergeAttributes(...)`. |
| `SecurityContextParser` | `parse(request): SecurityContext`. Throw `TokenVerificationException` for an invalid credential: the caller is treated as anonymous and a denial reports the token failure. Any other exception answers `500`. `parse` is synchronous and runs on the caller's thread unless token revocation or a blocking API key store is enabled, so a parser that does I/O must not run on an event loop otherwise. |
| `TokenStore` | `revoke(tokenId, ttl)` and `isRevoked(tokenId)`. Consulted on every authenticated request when revocation is enabled: keep lookups cheap. The entry must outlive the refresh token bound to the token id. |
| `ApiKeyStore` | `findByHash(hash): ApiKey?`. Called while parsing the security context; a store that does I/O needs `cosec.api-key.store-blocking=true`. |

## Authentication

| Extension point | Contract |
|---|---|
| `Authentication<C, CoSecPrincipal>` | Adds a login method for credentials of type `C`. The starter registers every `Authentication` bean. |
| `AbstractSwitchTenantAuthentication` | Base class for letting principals switch tenants. |
| `SocialUserPrincipalConverter` | `convert(provider, socialUser): Mono<CoSecPrincipal>` maps a social login's user to your principal. |

Tokens, login and revocation for users are in [Authentication](../integration/authentication.md).

## Auditing

Implement `AuditEventSink` (`publish(event)`, in `cosec-api`) to send audit events to your own system. Your bean
replaces both the logging sink and the Kafka sink. Publishing runs on the request path, so hand events off
asynchronously instead of blocking. An exception from the sink is logged and never changes the decision. The event
fields are described in [Observability](../operations/observability.md#audit-events).

**Next:** how to propose and land a change, in [`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/CONTRIBUTING.md), and the build rules and invariants for changing CoSec, in [`AGENTS.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/AGENTS.md).

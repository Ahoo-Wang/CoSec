---
name: cosec-custom-matcher
description: Extend CoSec 8.0 with Spring beans - AttributeProvider (external ext.* attributes fetched per request), MembershipProvider (roles and attached policies kept outside tokens), DecisionListener (audit, metrics, alerts), RateLimiter (where permits are counted) and Authenticator (how tokens or API keys are verified). Use when the policy language and built-in features cannot express a requirement. Do not use for writing policy documents.
---

# Extending CoSec

Extend only after confirming that a document cannot express the rule: conditions, bindings and rate limit rules cover
most needs (use `$cosec-policy-author`). CoSec 7.x custom action and condition matchers (`ActionMatcherFactory`,
`ConditionMatcherFactory`) have no 8.0 equivalent: conditions are a fixed AST, and new facts enter evaluation as
external attributes.

Human-facing reference: https://cosec.ahoo.me/architecture/extending. In a CoSec checkout the ports live in
`core/spi/src/main/kotlin/me/ahoo/cosec/spi/` (`attribute/`, `authn/`, `membership/`, `ratelimit/`, `token/`,
`apikey/`, `snapshot/`, `audit/`; module `cosec-spi`, which depends only on the model, the contracts and coroutines), and
the ones that carry runtime types in `core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/` (`event/`, `attribute/` for
`BlacklistSource`); the starter wires them in
`host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/pipeline/CoSecPipelineAutoConfiguration.kt` and
`CoSecPipelineIntegrationAutoConfiguration.kt`. A library that only implements a `cosec-spi` port depends on
`cosec-spi` alone. Never opt in to `@InternalCoSecApi` declarations: they are CoSec-internal. Inspect the target version's interfaces before writing code.

## Rules for every extension

- **Never block the calling thread.** Every SPI is a Kotlin `suspend` function and may run on an event loop. Wrap a
  blocking client with `withContext(yourBoundedDispatcher)`; never use `runBlocking`, `GlobalScope` or
  `Dispatchers.IO` inside the pipeline.
- **Report failure by throwing**, never by returning a default. CoSec turns a failure into the safe outcome (503 or the
  statement's `onMissing`); a made-up "not blocked" or "no roles" answer can let a request through.
- **Do not change the decision.** Extensions supply facts or observe outcomes; only documents decide.
- Do not change the SPI interfaces; ask first if one seems insufficient.

## Registration

The starter collects these beans:

| SPI | Package | Beans | Effect |
|---|---|---|---|
| `AttributeProvider` | `me.ahoo.cosec.spi.attribute` | any number (ordered) | adds `ext.*` attributes to the catalog and fetches them |
| `DecisionListener` | `me.ahoo.cosec.runtime.event` | any number, unique `name` | hears every decision event |
| `MembershipProvider` | `me.ahoo.cosec.spi.membership` | at most one; required with API keys | answers memberships for tokens marked `cosec.membership: "provider"` and for every API key's principal |
| `RateLimiter` | `me.ahoo.cosec.spi.ratelimit` | one; yours replaces the Redis or local limiter | counts rate-limit permits |
| `Authenticator` | `me.ahoo.cosec.spi.authn` | one; yours replaces the built-in token and API key authenticator | turns credentials into a principal |
| `BlacklistSource` | `me.ahoo.cosec.runtime.attribute` | one; the starter wraps it in `BlacklistAttributeProvider` | says who and what is blocked |
| `AuditEventSink` | `me.ahoo.cosec.spi.audit` | any number; each becomes a BEST_EFFORT listener named after its bean | 7.x-style audit events |
| `ControlStore` | `me.ahoo.cosec.control.store` | one, with `cosec-control`; replaces `CoCacheControlStore` | where the control plane keeps its records |

Declare them as ordinary `@Bean`s. There is no `META-INF/services` registration for these SPIs.

Not an extension point: `RequestGate` (`me.ahoo.cosec.runtime`), what the web adapters call. `Pipeline` is one;
`IdentityGate` is the other, for a service behind a CoSec gateway (`cosec.pipeline.mode=gateway`). Both build every
outcome through `StatusMapping`; do not write a third.

## AttributeProvider: external attributes

A provider declares `ext.<provider>.<name>` paths with a type (`STRING`, `BOOL`, `STRING_SET`, `IP`) and fetches
their values when a request's statements need them:

```kotlin
class RiskAttributeProvider(private val risk: RiskClient) : AttributeProvider {
    override val attributes = listOf(AttributeDeclaration("ext.risk.level", AttributeType.STRING))
    override val timeout: Duration? = 200.milliseconds // kotlin.time; null uses cosec.authorization.attributes.timeout

    override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
        val level = risk.level(request.subject.id, request.remoteIp) ?: return emptyMap() // absent, not failed
        return mapOf("ext.risk.level" to level)
    }
}

@Bean
fun riskAttributeProvider(risk: RiskClient): AttributeProvider = RiskAttributeProvider(risk)
```

```json
{ "effect": "deny", "action": "order:ship", "condition": { "eq": ["ext.risk.level", "high"] } }
```

- A value left out is **absent** (comparisons with it are false). A thrown exception, a timeout, or a value of the
  wrong type makes every attribute asked for **unavailable**: each statement reading one follows its `onMissing`
  (`indeterminate`, the default, answers 503 unless a DENY matches; `nomatch` treats the statement as not matching).
- `AttributeRequest` carries only settled facts: subject, resolved scope (null in stage A), action, resource, remote IP,
  request id, device id. It never carries another attribute's value.
- `AttributeDeclaration(..., stage = Stage.A)` fetches on every request before the scope is resolved; keep it for
  what decides what to load (the blacklist). The default, stage B, fetches only what the request's statements read.
- Documents compile only against paths some registered provider declares, or the platform document declares in
  `attributes` (declare them there so every instance and the control plane compile the same documents). A path declared twice, by two
  providers, fails startup. A document reading a path this instance cannot fetch makes those requests unavailable.
- **Register the same providers, with the same types, on every data-plane instance and on the control-plane host.**
  The control plane checks a change against its own host's providers; an instance without the provider cannot compile
  the candidate, reports `CANDIDATE_ERROR` and blocks the shadow window, and a change published without a window makes
  that instance answer 503 for the document.
- Built-in providers: `Ip2RegionAttributeProvider` (`ext.ip2region.country`, `.province`, `.city`, `.isp`, with
  `cosec-ip2region`) and `BlacklistAttributeProvider(source)` (`ext.blacklist.principal`, `.ip`, `.device`; the starter
  registers it when you declare a `BlacklistSource` bean, such as `InMemoryBlacklistSource`, and logs an INFO line
  when there is none). A blocked request meets the built-in DENY;
  a failing blacklist source is 503, never "not blocked".

## MembershipProvider: memberships outside tokens

For principals whose roles change often, must be revoked at once, or do not fit in a token, and for the principals of
API keys, which carry no memberships of their own. Tokens carrying `cosec.membership: "provider"` (and no
`roles`/`policies` claims) and every API key ask it:

```kotlin
@Bean
fun membershipProvider(directory: Directory): MembershipProvider = MembershipProvider { tenant, principal ->
    val grants = directory.grants(tenant.value, principal.value) // suspend, non-blocking
        ?: return@MembershipProvider MembershipAnswer.Unknown // not in the directory: its credentials are refused
    MembershipAnswer.Known(
        Memberships(
            Memberships.Source.PROVIDER,
            roles = grants.rolesBySpace.mapKeys { SpaceId(it.key) }.mapValues { (_, r) -> r.map(::RoleId).toSet() },
            attachments = grants.policies.map(::PolicyId),
            active = !grants.disabled,
        )
    )
}
```

Answer `MembershipAnswer.Unknown` for a principal you do not know and `active = false` for a deactivated one: its API keys and its
provider-marked tokens are then refused as `token-invalid` (IDN-3). Never answer empty memberships for an unknown
principal: that is a known principal with no roles, whose keys keep working. Answer the principal's memberships, not a
key's: the pipeline narrows them to the key's `roles` cap itself. With `cosec-cocache`, a `MembershipLoader` bean
following the same contract gets `CoCacheMembershipProvider` from the starter. A thrown
exception or a late answer (`cosec.authorization.membership.timeout`, default 500ms) fails the request with 503; it
never counts as "no memberships"; so does an answer whose source is not `PROVIDER`. The default space's key is
`SpaceId.DEFAULT` (`""`).
`InMemoryMembershipProvider` exists for tests.

## DecisionListener: observing decisions

```kotlin
@Bean
fun denyAlerts(alerts: Alerts): DecisionListener = object : DecisionListener {
    override val name = "deny-alerts"
    override suspend fun onDecision(event: DecisionEvent) {
        if (event.decision is Decision.Deny && event.subject.authenticated) alerts.record(event.requestId, event.by)
    }
}
```

- `BEST_EFFORT` (default): queued off the request path into a bounded queue; a full queue drops the event.
- `Delivery.REQUIRED`: runs before the response, within `cosec.audit.required.timeout` (1s); a failure or timeout turns
  a permit into 503. Make it idempotent by `event.id`. Use it only for an audit that must not be lost.
- Events carry the decision, deciding statement (`by`) and binding (`via`), break-glass flag, snapshot revisions,
  subject, scope, action, resource, request summary, attribute statuses (never values), trace ids. They carry no
  token, header or external attribute value. `event.evaluationTrace` holds the engine's trace at
  `cosec.pipeline.trace-level` (null at `off`, the default).
- Built-in listeners: the audit log (`cosec.audit.enabled`), Kafka (`cosec-kafka`), Micrometer, OpenTelemetry,
  shadow. A 7.x `AuditEventSink` bean is picked up as is: the starter wraps it (`AuditListener.of(sink, beanName)`).
- BEST_EFFORT queues hold `cosec.observability.listener.queue-capacity` (1024) events each; on shutdown they drain for
  up to `cosec.observability.listener.drain-timeout` (5s). A service in gateway mode registers no listeners.

## RateLimiter: where permits are counted

```kotlin
fun interface RateLimiter {
    suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult
}
```

Implement `me.ahoo.cosec.spi.ratelimit.RateLimiter`. The rules
stay in the documents; the limiter only counts. Return `RateLimitResult(allowed, limit, remaining, resetAfter)`; use
`RateLimiter.window(limit)` for the same sliding window as the built-in limiters. Throw when the store fails: the
rule's `strictFailure` then decides (skip, or 503). The built-ins are `ReactiveRedisRateLimiter` (with a
`ReactiveStringRedisTemplate`) and `LocalRateLimiter` (per JVM).

## Authenticator: verifying credentials

```kotlin
fun interface Authenticator {
    suspend fun authenticate(credentials: Credentials): AuthnResult
}
```

`Credentials` holds the bearer token (`Authorization` header, cookie, or query when allowed) and the API key header.
Return `AuthnResult.Authenticated(AuthenticatedPrincipal(id, tenant, memberships, attributes, credential), "method")`,
`Anonymous` (no credentials), `Rejected(reason)` (401 `token-*` on deny), or `Unavailable(cause)` (503). Memberships
you pass have source `Memberships.Source.CLAIMS` (`MembershipClaims.parse(roles, policies)` builds them from
`role`/`role@space` strings); pass `null` to ask the `MembershipProvider`. A bean of yours replaces the built-in `CompositeAuthenticator`
(JWT with revocation, plus API keys when enabled): compose it yourself, for example
`CompositeAuthenticator(myTokenAuthenticator, apiKeyAuthenticator)`, if you still need API keys.

On a gateway, set `credential` (`Identity.Credential(method, id, expiresAt)`, method `JWT` or `API_KEY`, never the
credential's value): without it the principal is forwarded to no backend, and with `expiresAt = null` the gateway
answers 500 rather than forward an identity that never expires.

## ControlStore: the control plane's records

Replace `CoCacheControlStore` only to keep control-plane records outside Redis. Besides keyed, conditional reads and
writes, implement `documents()` (every document with indexed revisions; may name more, never fewer),
`openChanges()` (changes that may still be open; may name more, never fewer) and `acquireLease` / `releaseLease`
(release only while the holder still has it). A failed read throws; never answer "absent". Start from
`InMemoryControlStore` and the KDoc of `core/control/src/main/kotlin/me/ahoo/cosec/control/store/ControlStore.kt`.

## Verification

- Unit-test the extension directly (`runTest`), including its failure path: a provider that throws, a listener that
  times out, a limiter whose store fails.
- Add one context test that starts the application with the bean and shows the effect end to end: a document reading
  the `ext.*` path compiles and decides, a provider-marked token gets its roles, a listener receives the event.
- Check that nothing blocks: in a CoSec checkout, `RuntimeBoundaryTest` and the BlockHound tests guard the built-ins;
  run the module's `check` (tests and detekt).

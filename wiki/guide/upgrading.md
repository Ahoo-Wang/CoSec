# Upgrading

What changes between versions and what to do about it. Release notes for every version are on
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases).

## 6.1 to 7.0

### Breaking changes

**A principal policy that does not exist fails authorization.** When a principal lists a policy id that the
`PolicyRepository` does not return (deleted, never written, lost from Redis), `SimpleAuthorization` now fails with
`MissingPolicyException`, which the pipeline maps to `500`. Before, the id was skipped and the remaining policies
decided: when the missing policy was a deny, such as one that suspends an account, a global allow let the request
through. `AuthorizationChecker` answers `false` for such a principal, and an explanation reports the `500` with a
`MISSING_POLICY` warning.

Before upgrading, find principals (tokens, API keys, user records) that list policy ids your repository does not
have, and either write those policies or remove the ids. `PolicyRepository.getPolicies` itself is unchanged: it
still returns the policies it found, so custom repositories need no change.

## 6.0 to 6.1

### Breaking changes {#breaking-changes-1}

**Rate limits are rules, not policy conditions.** The `rateLimiter`, `groupedRateLimiter`, `redisRateLimiter` and
`redisGroupedRateLimiter` conditions are removed, together with `TooManyRequestsException`. A policy that still uses
one no longer parses. Local policy files that use them are skipped with an `ERROR` log, and policies stored in
Redis that use them fail their reads, which denies requests. Before upgrading, move each limit into a
[rate limit rule](./rate-limiting.md#rules) following the
[migration table](./rate-limiting.md#migrating-from-rate-limit-conditions). Three behaviors change with it:

- Only requests that authorization allows spend permits. Before, a limit in a policy condition also counted
  requests that another layer then denied.
- Each quota belongs to its rule `id`. Before, Redis limiters with identical settings shared one quota.
- Redis counters move from `<key-prefix>:<settings hash>:…` to `<key-prefix>:<id>:…`, so counters restart. During a
  rolling upgrade, old and new instances count separately.

**Responses can carry rate limit headers.** When a rule matches, responses include `RateLimit-Limit`,
`RateLimit-Remaining` and `RateLimit-Reset`, and `429` responses include `Retry-After`
([Responses](./rate-limiting.md#responses)).

**The `Authorization` bean is decorated.** With rate limiting enabled (`cosec.limiter.enabled`, default `true`)
the auto-configured bean is a `RateLimitingAuthorization` around `SimpleAuthorization`, and with
`micrometer-support` a `MeteredAuthorization` around that. Code that casts the bean to `SimpleAuthorization` must
inject it as `Authorization`, or unwrap `Delegated.delegate`.

**Decision types gained a field.** `AuthorizationDecision` and `SecurityDecision` have a new `rateLimit` property.
Kotlin code that destructures them or calls `copy` by position must be recompiled.

### New features

Everything below is additive. Features that change what a request experiences are off by default.

| Feature | Default | Where |
|---|---|---|
| Rate limit rules: per-IP, per-user or shared quotas, counted in Redis or in memory | on, once a rule file exists | [Rate Limiting](./rate-limiting.md) |
| Explain a decision, for a described principal or a real token or API key | off (`cosec.explain.enabled`) | [Troubleshooting](./troubleshooting.md#explain-a-decision) |
| Shadow mode: try candidate policies and app permissions on real traffic | off (`cosec.shadow.enabled`) | [Policies](./policies.md#trying-changes-in-shadow-mode) |
| `AuthorizationChecker`: check permissions in code, one action or many | always available | [Web Integration](./web-integration.md#checking-permissions-in-code) |
| Object-level rules with `resource.<name>` parts | opt in per policy | [Policies](./policies.md#object-level-rules) |
| `time` condition: validity periods and daily windows, self-expiring grants | opt in per policy | [Policies](./policies.md#time-conditions) |
| Policy history and rollback | off (`cosec.authorization.cache.history.enabled`) | [Policies](./policies.md#policy-history-and-rollback) |
| Declarative policy test cases (`PolicyTestKit`) | library | [Policies](./policies.md#policy-test-cases) |
| Access reviews (`AccessAnalyzer`): who can do what | library | [Operations](./operations.md#access-reviews) |
| API keys for machine clients, in memory or in Redis | off (`cosec.api-key.enabled`) | [Authentication](./authentication.md#api-keys) |
| `cosec.authorization` decision metrics | on with `micrometer-support` and a `MeterRegistry` | [Operations](./operations.md#observability) |

New properties: `cosec.limiter.locations`, `cosec.explain.enabled`, `cosec.shadow.*`, `cosec.metrics.enabled`,
`cosec.api-key.*` and `cosec.authorization.cache.history.*` (see [Configuration](./configuration.md)).

### Upgrade checklist

1. Search your policies, including those stored in Redis, for the four removed condition types, and write the
   equivalent rate limit rules under `classpath:cosec-rate-limit/`.
2. Replace any `TooManyRequestsException` thrown by custom matchers with a rate limit rule.
3. Check that clients and proxies tolerate the new rate limit headers.
4. Replace casts of the `Authorization` bean to `SimpleAuthorization`.
5. Upgrade, then confirm in the startup log that every rate limit rule file loaded. An unreadable rule file stops
   startup.
6. Optionally, add `PolicyTestKit` suites for the decisions you rely on before you change policies again.

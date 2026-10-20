# Upgrading

What changes between versions and what to do about it. Release notes for every version are on
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases).

## 6.1 to 7.0

### Breaking changes

CoSec 7.0 runs on [CoCache 6.4](https://cocache.ahoo.me/guide/migration-6) and
[CosId 4.0](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md). CoCache 6 changed how values are
stored in Redis, so this upgrade needs a short stop and a one-off data copy.

**Switch every instance at once.** CoCache 6 changed the Redis value layout and the invalidation channel, so 6.x
and 7.0 instances cannot share a cache: neither sees the other's writes. There is no rolling upgrade: stop every
6.x instance, migrate the data, then start 7.0. The Redis keys stay the same (`cosec:policy:<id>`,
`cosec:global:policy`, `cosec:app:permission:<appId>`, `cosec:role:permission:<roleId>[@<spaceId>]`,
`cosec:token:revoked:<tokenId>`, where `cosec` is [`cosec.authorization.cache.key-prefix`](./configuration.md#cache));
only the values change format. Policy history, API keys and rate limit counters are not CoCache data and keep their
keys and formats.

**Migrate the 6.x data before 7.0 starts.** Redis is the only copy of policies, app permissions and role grants,
and CoCache 6 cannot read CoCache 5 values: every 6.x value has to be converted before a 7.0 instance reads it.

::: warning TODO: data migration
Run CoCache's 5.x migrator (version TBD, planned for the next CoCache minor release) on the namespaces
`cosec:policy`, `cosec:global` (a CoCache 5 native set), `cosec:app:permission` and `cosec:token:revoked` before
starting 7.0 instances. Role grants (`cosec:role:permission`, CoCache 5 native sets) are deliberately not on this
list: they move to app-scoped keys in 7.0 with a migration of their own (TODO). Do not start 7.0 against unmigrated
data. This section will describe the exact steps, the
migrator's report and rollback once that release is available.
:::

**Write through CoSec, not straight to Redis.** Every CoCache 6 value starts with a binary header, so data written
with `SET` or `SADD` (for example app permissions and role grants written by an administration service) is not read.
Write policies with `PolicyRepository.setPolicy`, and app permissions and role grants through the
`AppPermissionCache` and `RolePermissionCache` beans. Their API is CoCache 6's:

| 6.x (CoCache 5) | 7.0 (CoCache 6) |
|---|---|
| `me.ahoo.cache.api.Cache` | `me.ahoo.cocache.api.Cache` |
| `cache[key] = value`, `set(key, ttlAt, value)` | `put(key, value)`, `put(key, value, ttl: Duration)` |
| `evict(key)` | `invalidate(key)` |
| `GlobalPolicyIndexCache` is a `SetElementsCache`: `addElements` / `removeElements` | it is a `SetCache`: `add` / `remove` |

**Redis requirements.** CoCache 6 needs Redis 7.0 or later (or Valkey 7.2 or later) and builds its own Lettuce
client from `spring.data.redis.*`: Jedis is not supported, and a static master-replica setup fails at startup.
A minimal Redis ACL must also allow `+hkeys +hget` (keep `+hgetall`).

**Local copies live at most 5 minutes by default.** CoCache 6 always bounds how long a local copy lives
(`localMaxAge`, 5 minutes by default) and then reads Redis again; it has no unlimited setting. In 6.x a local copy of
a policy or permission lived until a change invalidated it. `expire-after-write` of
`cosec.authorization.cache.policy`, `role` and `token` now sets this bound, so it applies to every local copy.

**CoSec creates its caches itself.** The caches are no longer declared with `@EnableCoCache`, and the 6.x component
beans (`PolicyCache.KeyConverter`, `PolicyCache.ClientSideCache`, `PolicyCache.DistributedCache`, and the same for
the other caches) are gone. Tune a cache with a CoCache `CacheSpecCustomizer` bean or
`cocache.caches.<CacheName>.*`, where the cache names are `PolicyCache`, `GlobalPolicyIndexCache`,
`AppPermissionCache`, `RolePermissionCache` and `RevokedTokenCache`. Two settings stay CoSec's: the storage layout
(`key-prefix` there is ignored) and, for policies and permissions, "never expire, and fail when Redis cannot be
read", which is applied after any customizer or property. `CacheProperties` lost `globalPolicyIndexKey`,
`policyKeyPrefix`, `appPermissionKeyPrefix`, `rolePermissionKeyPrefix` and `revokedTokenKeyPrefix`. CoCache itself
renamed or removed several `cocache.*` properties (for example `cocache.redis.strict-failure`); a removed one fails
startup and names its replacement.

**Id generators are `StringIdGenerator`s.** In CosId 4, `UuidGenerator` and `CosIdGenerator` implement only
`StringIdGenerator`. `AuditingAuthorization`, `JwtTokenConverter`, `JustAuthProvider`, `ReactiveRequestParser` and
`ServletRequestParser` take a `StringIdGenerator`, and the starter injects one: a `CosIdGenerator` or other id
generator bean is still used when present, `UuidGenerator` otherwise. Code that passes an `IdGenerator` keeps
compiling; code that declares an `IdGenerator` to hold a `CosIdGenerator` must declare a `StringIdGenerator`. If the
application uses CosId itself, follow CosId's
[migration guide](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md).

### Upgrade checklist

1. Check that Redis is 7.0 or later and that its ACL allows `+hkeys +hget`.
2. Replace direct Redis writes of app permissions and role grants with writes through CoSec.
3. Replace 6.x cache component beans with a `CacheSpecCustomizer` or `cocache.caches.<CacheName>.*`, and change
   `IdGenerator` declarations used for a `CosIdGenerator` to `StringIdGenerator`.
4. Stop every 6.x instance.
5. Migrate the data (see the TODO above).
6. Start the 7.0 instances.

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

### Upgrade checklist {#upgrade-checklist-1}

1. Search your policies, including those stored in Redis, for the four removed condition types, and write the
   equivalent rate limit rules under `classpath:cosec-rate-limit/`.
2. Replace any `TooManyRequestsException` thrown by custom matchers with a rate limit rule.
3. Check that clients and proxies tolerate the new rate limit headers.
4. Replace casts of the `Authorization` bean to `SimpleAuthorization`.
5. Upgrade, then confirm in the startup log that every rate limit rule file loaded. An unreadable rule file stops
   startup.
6. Optionally, add `PolicyTestKit` suites for the decisions you rely on before you change policies again.

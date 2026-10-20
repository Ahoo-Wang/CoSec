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

**Root is off by default, limited to the platform tenant, and subject to the blacklist.** Before, any principal
whose id was `cosec` (or the `-Dcosec.root` system property) bypassed every check, including the blacklist, and
`cosec.authorization.root-enabled` defaulted to `true`. A user tenant whose identity provider let a user pick the
id `cosec` handed that user platform-wide access. Now:

- `cosec.authorization.root-enabled` defaults to `false`: no principal is root unless you enable it.
- When enabled, `RootPrincipalMatcher.DEFAULT` treats a principal as root only if its id is the root id **and** its
  tenant is the platform tenant (`(platform)`, `Tenant.PLATFORM_TENANT_ID`). The root id in a user tenant, in the
  default tenant, or on a principal without a tenant is an ordinary principal.
- The blacklist is checked before root, so a blacklisted request from root is denied. In an explanation the
  `BLACKLIST` layer now comes before `ROOT`, and `ExplainLayer` lists them in that order.

If you rely on root, set `cosec.authorization.root-enabled=true` and issue the root principal's token with
`tenantId` `(platform)`. Code that builds `SimpleAuthorization`, `AuthorizationExplainer` or `ShadowAuthorization`
directly keeps `RootPrincipalMatcher.DEFAULT` as its default, with the new tenant rule. `PolicyTestKit` follows
the starter instead: no principal is root unless you pass a `RootPrincipalMatcher` to `run`. If root must work some
other way, provide your own `RootPrincipalMatcher` bean.

**Role grants are scoped to an app and a space.** In 6.x a role grant was stored per role and space, without the
app, and the client chose both the app (`CoSec-App-Id`) and the space (`CoSec-Space-Id`). A grant, `*` included,
therefore applied in every app that defined the same permission ids, and changing `CoSec-Space-Id` picked up the
grants of a same-named role in another space. In 7.0:

- Grants are keyed by app, role and space. The Redis key moves from `cosec:role:permission:<roleId>[@<spaceId>]`
  to `cosec:app:role:permission:<appId>:<roleId>[@<spaceId>]`, and `RolePermissionCache` takes an
  `AppSpacedRoleId(appId, roleId, spaceId)` instead of a `SpacedRoleId`. App ids must not contain `:` and role
  ids must not contain `@`; the OpenAPI app permission generator replaces `:` in the title.
- A request with `CoSec-Space-Id: x` is evaluated with only the principal's roles written `role@x`; without the
  header, with only the roles without a suffix. Issue `role@space` in tokens and API keys for every space a
  principal holds a role in, after checking membership ([Tokens](./authentication.md#tokens)).
- Authorization never reads 6.x keys. Until they are migrated, roles have no grants and role-based requests are
  denied (fail closed).
- `AppRolePermissionRepository.getAppRolePermission` keeps its signature. `SimpleAuthorization` now passes the
  roles that apply in the space, without the `@space` suffix; a custom repository must return only the grants
  made for that app and space.
- The explainer adds the `NO_ROLE_IN_SPACE` warning.
- `AccessAnalyzer` and `PolicyTestKit` scope role grants the same way. A grant role written without a suffix is
  granted in the default space only; write `role@space` for grants in another space. `whoCan` takes grants from the
  request's space, `grantsOf` reaches a grant only through a role held in its space and takes an optional
  `spaceId`, and `AccessRule` adds `spaceId`. Test suites add `appRoles` for grants made in one app; `roles` still
  grants in every app. Malformed roles now fail instead of never matching.

**Path variables belong to the statement that captured them.** Before, `request.path.var.*` read whatever the
last matching `path` action of the request had captured, in any statement, policy or layer. A condition could
therefore depend on another statement: a deny statement that never matched could make an `all`-action allow
match (adding a deny widened access), a policy condition read the variables of the global layer, and a rate limit
rule without a `path` action read the variables of a policy. Now:

- the variables are cleared before every statement, permission and rate limit rule matches its action, and before
  policy and app permission conditions, so a condition only sees what its own `path` action captured;
- reading them where no `path` action of the same statement or rule captured them is a load error,
  `PathVariableScopeException`: in a policy or app permission `condition`, in the condition of a statement or
  permission whose action is not `path`, in the `condition` or `key` of a rate limit rule whose action is not
  `path`, and in any action's configuration, such as a `path` template `/orders/#{...PATH_VARIABLES...}`: a template
  is evaluated before its action matches, when the variables are not its own ([Path variables](./policies.md#actions)).

Parsing such a policy, app permission or rate limit rule fails, and so does constructing `PolicyData`,
`AppPermissionData` or `RateLimitRule` with one. `RedisPolicyRepository.setPolicy` rejects it. A local policy file
with one stops the application from starting. A policy already stored in Redis with one fails its reads, which
denies requests with `500`.

Before upgrading, search policies, app permissions and rate limit rules for `request.path.var.` and
`PATH_VARIABLES`. Move each reference into a statement whose `path` action captures the variable, for example
replace `"action": "*"` with the pattern that declares it, and move a policy-level condition into the conditions of
its statements.

**`inRole` follows roles into their space.** With roles issued as `role@space`, a bare `inRole` value now also
matches the principal's `role@<request space>`, so a deny such as `inRole: suspended` keeps matching after tokens
switch from `suspended` to `suspended@team-a`. A role held without a suffix still matches in every space. A value
written `role@space` is compared literally with the principal's roles, as before. Conditions that read
`context.principal.roles` some other way (`in`, `spel`, `ognl`) still see the raw strings, `suspended@team-a`
included, and stop matching a bare role name once tokens carry `role@space`
([Context conditions](./policies.md#context-conditions)).

**Any invalid local policy file stops startup.** `LocalPolicyLoader` used to log and skip a policy file it could
not parse or build (malformed JSON, an unknown matcher type, an invalid option), which could silently drop a deny.
Now any such file fails loading, as rate limit rule files already did, and the error names the file. A location
that does not exist is still skipped with a warning ([Troubleshooting](./troubleshooting.md#startup-fails-loading-a-policy-file)).

**Rate limit rule keys are checked when the rule loads.** A `key` that is not a supported part (for example
`request.ip` instead of `request.remoteIp`) used to load and then fail every matching request with `500`, after
earlier rules had spent their permits. Constructing or loading such a rule now fails
([Rate limiting](./rate-limiting.md)).

**OpenAPI-generated app ids replace `:` with `-`.** `OpenAPIAppPermissionGenerator` derives the app id from the
OpenAPI title, and app ids may no longer contain `:`. An app whose title contains `:` (for example `orders:api`)
gets the id `orders-api`; clients must send the new id in `CoSec-App-Id`, and its role grants must be written for it.

### CoCache 6 and CosId 4

CoSec 7.0 runs on [CoCache 6.6](https://cocache.ahoo.me/guide/migration-6) and
[CosId 4.0](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md). CoCache 6 changed how values are
stored in Redis, so this upgrade needs a short stop and a one-off data migration
([Migrating the data](#migrating-the-data)).

**Switch every instance at once.** CoCache 6 changed the Redis value layout and the invalidation channel, so 6.x
and 7.0 instances cannot share a cache: neither sees the other's writes. There is no rolling upgrade: stop every
6.x instance, migrate the data, then start 7.0. The Redis keys of policies (`cosec:policy:<id>`), the global
policy index (`cosec:global:policy`), app permissions (`cosec:app:permission:<appId>`) and revoked tokens
(`cosec:token:revoked:<tokenId>`) stay the same, where `cosec` is
[`cosec.authorization.cache.key-prefix`](./configuration.md#cache); only their values change format. Role grants
move to app-scoped keys (see above). Policy history, API keys and rate limit counters are not CoCache data and keep their
keys and formats.

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

**Unmigrated data fails closed.** A 7.0 instance that reads a value CoSec 6.x wrote fails the read
(`CacheReadException`, caused by CoCache's `ForeignFormatException`) and leaves the value in Redis, so the request is
denied with `500`. Policy and permission caches always keep this behavior, even with
`cocache.defaults.foreign-format=reclaim`: with no loader, a reclaim would delete the only copy.

**Writes before the migration.** Every `setPolicy` updates the global policy index, which CoCache 6 does not
overwrite while it still holds the 6.x native SET: the publish fails with `CacheWriteException`, caused by
`ForeignFormatException` naming the index key (`cosec:global:policy`), the index is left as it was, and the
migration still converts it. `setPolicy` completes with this error (a local policy
with `force-refresh=true` stops startup, see [troubleshooting](./troubleshooting.md#publishing-a-policy-fails-before-the-migration)),
and requests are still denied with `500`. Other writes are not checked: a global policy's content (written before
the index) and app permissions replace the 6.x value of their key in the 7.0 format. The migration then skips that
key and keeps no backup of it, so a rollback cannot restore its 6.x value. Role grants go to new keys, and one
written before its migration is reported there as a conflict. This is why nothing may write before the migration
has finished.

**The global policy index is checked against the policies.** The index and the policy contents are separate Redis
keys. In 6.x an indexed id without content was skipped, so a lost global `DENY` silently stopped applying; 7.0
fails the read with `MissingPolicyException` and the request is denied with `500`
([troubleshooting](./troubleshooting.md#every-request-fails-with-500)). An indexed id whose content is not of type
`global` (a concurrent or half-finished republish) is ignored instead of applying to everyone.

### Migrating the data

CoCache 6 cannot read the values CoSec 6.x stored with CoCache 5, and Redis is the only copy of policies, app
permissions and role grants. Two migrations convert them, both classes you call from a 7.0 process (an admin
application or a one-off job using the starter with `cache-support`), not actuator endpoints.

- `CoSecCoCache5Migration` converts policies, the global policy index, app permissions and revoked tokens in place
  with CoCache's [`CoCache5Migrator`](https://cocache.ahoo.me/guide/migration-6), backing up every original value
  first. The policy history (`cosec:policy:history:*`) is not CoCache data and is left alone. Revoked tokens are
  migrated whether or not the migrating process enables token revocation.
- `RolePermissionKeyMigration` copies role grants to their new app-scoped keys, one app at a time
  ([Migrating role grants](#migrating-role-grants)). The 6.x keys stay as they were.

In order:

1. **Stop every writer.** Stop all 6.x instances, and anything else that writes CoSec keys (an administration
   service writing policies or grants), and start no 7.0 instance that writes. Nothing may write during the
   migration: a key changed while it is migrated aborts the run, and a key 7.0 wrote first is skipped without a
   backup ([Writes before the migration](#cocache-6-and-cosid-4)).
2. **Run the CoCache migration.** First a dry run, which reads and decodes every value and writes nothing, then the
   migration. Read the report of each: per cache, the keys `read`, `written`, `skipped` (already migrated) and
   `negatives` (cached misses, migrated as cached misses). A value that cannot be decoded aborts the run with a
   `CoCache5MigrationException` naming the key; fix it and run again. Migrated keys are skipped, so a run can be
   repeated.

   ```kotlin
   @Bean
   fun migrateCoSecData(migration: CoSecCoCache5Migration) = ApplicationRunner {
       log.info { "dry run: ${migration.dryRun()}" }
       log.info { "migrated: ${migration.migrate()}" }
   }
   ```

   Set `cosec.authorization.cache.migration.legacy-key-prefix` or `missing-guard-sentinel` when 6.x used a key
   prefix other than the current one or a non-default `cocache.redis.missing-guard-sentinel`, and `backup-prefix` to
   move the backups away from `cocache5-backup:`. Give the 6.x key prefix exactly as it was configured. 6.x did not
   trim a trailing `:` (`myapp:` stored policies under `myapp::policy:`), but 7.0 does (`myapp:policy:`). With such
   a prefix the migration copies each value to its 7.0 key instead of converting it in place, and leaves the 6.x keys
   unchanged; delete them yourself once 7.0 runs well, except `myapp::policy:history:*`, which 7.0 still uses.
3. **Migrate role grants** with `RolePermissionKeyMigration`, once per app, dry run first
   ([Migrating role grants](#migrating-role-grants)).
4. **Verify.** Check the reports, then read a few policies, global policies and grants through a 7.0 process (for
   example with [explain](./troubleshooting.md#explain-a-decision)). A read that still fails with
   `ForeignFormatException` names a key that was not migrated.
5. **Start the 7.0 instances.** Local policy files are written to Redis on startup only when their id is not there
   yet (`force-refresh=false`), so startup first reads the stored copy. If that copy cannot be read, because it was
   not migrated or no longer passes 7.0's [path variable](./policies.md#actions) checks, startup fails
   ([troubleshooting](./troubleshooting.md#startup-fails-reading-a-stored-policy)).
6. **Clean up.** Once 7.0 runs well, delete the backups with `CoSecCoCache5Migration.dropBackups()`. Delete the 6.x
   role grant keys with `RolePermissionKeyMigration.deleteLegacy()` only after `migrate` has run for **every** app:
   it deletes the legacy grants of all apps, not only the last one migrated. Review each report's `skipped` and
   `conflicts` first, then compare the keys `deleteLegacy(dryRun = true)` lists with the ones the reports migrated.

**Rollback.** Before the backups are dropped, 6.1 can be restored: stop every 7.0 instance and writer, call
`CoSecCoCache5Migration.rollback()`, and start 6.1. Rollback restores the snapshot taken by the migration:

- every migrated key (policies, app permissions, revoked tokens, the global policy index) gets its 6.x value back
  from its backup (type, content and remaining lifetime), so changes 7.0 made to it are lost, and a policy 7.0 made
  global leaves the index;
- keys 7.0 created after the migration, such as new policies or app permissions, have no backup and stay in the 7.0
  format, which 6.1 cannot read (requests that need them get `500`): write them again through 6.1, which overwrites
  them;
- with a 6.x key prefix ending in `:`, the copies 7.0 used are deleted and 6.1 reads its own keys, which were never
  changed;
- the 6.x role grant keys were never changed, unless `deleteLegacy()` ran.

#### Migrating role grants

This is step 3 of [Migrating the data](#migrating-the-data). 6.x grants do not record the app they were meant for, so CoSec cannot migrate them on its own: you name the
target app, and nothing is copied to every app. `RolePermissionKeyMigration` (a bean with `cache-support`) does
the copy. It is a class you call, not an actuator endpoint, because an endpoint that writes authorization data
would itself have to be secured and would stay exposed long after the one-off migration.

1. Decide which app each 6.x role (and space) belongs to. When roles of several apps share the old key space,
   split them with the `roleIds` argument.
2. Issue roles as `role@space` for every non-default space, in tokens and API keys.
3. Run the migration once per app from a 7.0 process against the same Redis, for example an admin application or
   a one-off job, while no instance serves traffic. Start with a dry run and read the report:

   ```kotlin
   @Bean
   fun migrateRoleGrants(migration: RolePermissionKeyMigration) = ApplicationRunner {
       val report = migration.migrate(appId = "order-app", dryRun = true) // then dryRun = false
       log.info { "migrated=${report.migrated} conflicts=${report.conflicts} skipped=${report.skipped}" }
   }
   ```

   Writes go through `RolePermissionCache`, so running instances drop their local copies. A run is idempotent:
   grants already migrated are reported as `unchanged`, and a target that holds different grants is reported as a
   conflict and kept. Keys holding a cached miss, keys that are not sets, and keys with an ambiguous role or space
   (more than one `@`, or an empty part) are reported as `skipped`.
4. Once every instance runs 7.0 and the grants of **every** app are migrated and verified, delete the old keys
   with `deleteLegacy()`. It deletes every legacy key, whichever app it was meant for, so run it last: review the
   `skipped` and `conflicts` of each report, then run `deleteLegacy(dryRun = true)` and compare the keys it lists
   with the ones the reports migrated. Until then a rollback to 6.1 still finds them; if grants changed through 6.x
   after a first run, run the migration again with `overwrite = true`. Reading a role that has no 7.0 grants yet
   leaves a short-lived lease marker (about 10 seconds) on its new key, even on a dry run; it holds no grant and
   expires on its own.

Until a role's grants are migrated to an app, requests that rely on them are denied in that app.

### Upgrade checklist

1. Check that Redis is 7.0 or later and that its ACL allows `+hkeys +hget`.
2. Search policies, app permissions and rate limit rules for `request.path.var.` and `PATH_VARIABLES` and fix
   their scope; check that every local policy file loads and every rate limit `key` is a supported part.
3. Find principals that list policies the repository does not have; decide the app of every 6.x role; issue
   `role@space` roles; enable root explicitly if you use it. Audit `in`, `spel` and `ognl` conditions over
   `context.principal.roles`, which see raw `role@space` strings; and if an OpenAPI title contains `:`, switch
   clients to the new `CoSec-App-Id`.
4. Replace direct Redis writes of app permissions and role grants with writes through CoSec, replace 6.x cache
   component beans with a `CacheSpecCustomizer` or `cocache.caches.<CacheName>.*`, and change `IdGenerator`
   declarations used for a `CosIdGenerator` to `StringIdGenerator`.
5. Stop every writer, migrate the data, verify, then start 7.0 ([Migrating the data](#migrating-the-data)).
   Delete legacy role grants only after every app is migrated.


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

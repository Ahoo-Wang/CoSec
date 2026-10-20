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
directly keeps `RootPrincipalMatcher.DEFAULT` as its default, with the new tenant rule. If root must work some
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

#### Migrating role grants

6.x grants do not record the app they were meant for, so CoSec cannot migrate them on its own: you name the
target app, and nothing is copied to every app. `RolePermissionKeyMigration` (a bean with `cache-support`) does
the copy. It is a class you call, not an actuator endpoint, because an endpoint that writes authorization data
would itself have to be secured and would stay exposed long after the one-off migration.

1. Decide which app each 6.x role (and space) belongs to. When roles of several apps share the old key space,
   split them with the `roleIds` argument.
2. Issue roles as `role@space` for every non-default space, in tokens and API keys.
3. Before 7.0 serves traffic, run the migration once from a 7.0 process against the same Redis, for example an
   admin application or a one-off job. Start with a dry run and read the report:

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
4. Roll out 7.0. 6.x instances keep reading the old keys meanwhile. If grants change through 6.x during the
   rollout, run the migration again with `overwrite = true`.
5. Once every instance runs 7.0 and the grants are verified, delete the old keys with `deleteLegacy()` (it also
   takes `dryRun`).

Until a role's grants are migrated to an app, requests that rely on them are denied in that app.

**Path variables belong to the statement that captured them.** Before, `request.path.var.*` read whatever the
last matching `path` action of the request had captured, in any statement, policy or layer. A condition could
therefore depend on another statement: a deny statement that never matched could make an `all`-action allow
match (adding a deny widened access), a policy condition read the variables of the global layer, and a rate limit
rule without a `path` action read the variables of a policy. Now:

- the variables are cleared before every statement, permission and rate limit rule matches its action, and before
  policy and app permission conditions, so a condition only sees what its own `path` action captured;
- reading them where no `path` action of the same statement or rule captured them is a load error,
  `PathVariableScopeException`: in a policy or app permission `condition`, in the condition of a statement or
  permission whose action is not `path`, and in the `condition` or `key` of a rate limit rule whose action is not
  `path` ([Path variables](./policies.md#actions)).

Parsing such a policy, app permission or rate limit rule fails, and so does constructing `PolicyData`,
`AppPermissionData` or `RateLimitRule` with one. `RedisPolicyRepository.setPolicy` rejects it. A local policy file
with one stops the application from starting, instead of being skipped like other invalid files. A policy already
stored in Redis with one fails its reads, which denies requests with `500`.

Before upgrading, search policies, app permissions and rate limit rules for `request.path.var.` and
`PATH_VARIABLES`. Move each reference into a statement whose `path` action captures the variable, for example
replace `"action": "*"` with the pattern that declares it, and move a policy-level condition into the conditions of
its statements.

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

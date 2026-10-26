---
title: "Upgrading"
description: "What breaks between CoSec versions and how to migrate."
---

# Upgrading

For teams moving an existing CoSec deployment to a newer version. Each section lists what breaks, what to change,
and a checklist; this page is the single home of migration steps. Release notes for every version are on
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases).

## 7.x to 8.0

### Spring MVC reads query parameters from the query string only {#spring-mvc-reads-query-parameters-from-the-query-string-only}

On Spring MVC, CoSec read a query parameter with `HttpServletRequest.getParameter`, which also returns the fields of
an `application/x-www-form-urlencoded` body. So for a form POST, the container parsed and consumed the body inside
CoSec's authorization filter, and a body field counted as a query parameter. It now reads the URL query string only,
decoded as WebFlux and Gateway decode it, and never touches the body
([Form POST body is empty after CoSec on Spring MVC](./troubleshooting.md#form-post-body-is-empty-after-cosec-on-spring-mvc)).

What a form field no longer sets on Spring MVC:

- `CoSec-App-Id`, `CoSec-Space-Id` and `CoSec-Device-Id`, and so `request.appId`, `request.spaceId` and
  `request.deviceId` in conditions and the app and space that role permissions use
  ([App and space](../integration/web.md#app-and-space));
- the `authorization` token read with `cosec.authorization.allow-query-token=true`;
- `getQuery(...)` in an `ognl` condition, a custom condition matcher or a `RequestAttributesAppender`.

Clients that send these as form fields must send them as headers or in the query string. A condition that matched a
form field this way no longer matches: an allow stops allowing, and a deny stops denying. Move such checks into the
application, after it has read the body; an authorization filter must not read request bodies.

## 7.0.0 to 7.0.1

The Redis `RateLimiter` bean is now named `cosecRedisRateLimiter` instead of `redisRateLimiter`, the name Spring Cloud
Gateway also uses ([the startup failure it caused](./troubleshooting.md#startup-fails-with-a-redisratelimiter-bean-conflict)).
Its type and conditions are unchanged, so injecting `RateLimiter` by type or replacing it with your own bean works as
before. Only code that refers to CoSec's bean by the name `redisRateLimiter` (`@Qualifier`, `getBean("redisRateLimiter")`,
`@ConditionalOnBean(name = …)`) must use `cosecRedisRateLimiter`.

### Templated path actions reject values that change the pattern {#templated-path-actions-reject-values-that-change-the-pattern}

Also in 6.1.2 and 8.0. A `#{...}` template in a `path` action, such as `/users/#{principal.id}/**`, used to be evaluated into a string that
was then parsed as a whole pattern, so a principal id or attribute containing pattern syntax changed what the
statement matched. Each template value now fills one literal part of the pattern, and a value that is empty, `.`,
`..`, or contains `*`, `?`, `{`, `}`, `\` or the path separator (`/`, or the `separator` option), and, with
`decodeAndParseSegments: false`, `%` or `;` is rejected ([Template values in `path` actions](../policies/language.md#template-values-in-path-actions)):

- an `allow` statement or permission no longer matches such a principal (`Implicit Deny`);
- a `deny` statement or permission fails such a principal's request closed with an error
  (`RejectedTemplateValueException`, a `500`), whatever its condition;
- a rate limit rule fails such a principal's matching requests closed the same way, and spends no permit;
- a templated matcher built in code with `PathActionMatcherFactory` and used outside a statement fails the same way.
  Code that deserializes statements, permissions or rate limit rules from JSON is unaffected; a custom deserializer
  that builds ALLOW statements itself can call `onRejectedTemplateValue(NO_MATCH)` on the action.

Principals whose ids or attributes contain these characters can no longer be served by templated statements. A
template that produced several segments, such as `#{principal.attributes['home']}/**` with `home` set to
`/users/alice`, is rejected the same way, and a template inside a `{...}` capture, such as `{name:#{...}}`, now
fails when the policy loads. What to do:

1. Find templated `path` actions: search policies, app permissions and rate limit rules for `#{` in an `action`.
2. If principal ids or the attributes they use can contain these characters, rewrite the statement as a static
   pattern plus an `eq` condition on the path variable ([example](../policies/language.md#template-values-in-path-actions)). `eq` compares strings, so any
   value works.
3. Check that the principals you expect still match
   ([Templated statement ignores one principal or fails with 500](./troubleshooting.md#templated-statement-ignores-one-principal-or-fails-with-500)).

## 6.1 to 7.0

7.0 tightens authorization in several places where 6.x could let a request through by mistake, and moves to
[CoCache 6.6](https://cocache.ahoo.me/guide/migration-6) and
[CosId 4.0](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md). CoCache 6 stores values in Redis in a
new format, so the upgrade needs a short stop of every instance and a one-off data migration. There is no rolling
upgrade.

Read the whole section before you start. Then work through the [checklist](#upgrade-checklist), the
[migration](#migrating-the-data), and keep the [rollback](#rollback) at hand until the backups are dropped.

The stricter authorization costs 6 to 9 % of `SimpleAuthorization` throughput compared with 6.1.0, about 0.2 µs
per decision; nothing needs to change for it ([what the 7.0 fixes cost](../architecture/overview.md#what-the-7-0-fixes-cost)).

### Breaking changes

Each row is one change; the link leads to what it means and what to do.

| Change | In one sentence |
|---|---|
| [Missing principal policies fail](#missing-principal-policies-fail) | A policy id a principal lists but the repository lacks now fails authorization with `500` instead of being skipped. |
| [Missing role permissions fail](#missing-role-permissions-fail) | A permission id a role is granted but its app's catalog lacks now fails authorization with `500` instead of being skipped. |
| [Root is off, platform-only, and blacklisted](#root-is-off-platform-only-and-blacklisted) | `root-enabled` defaults to `false`, root must belong to the platform tenant, and the blacklist is checked before root. |
| [Role grants belong to an app and a space](#role-grants-belong-to-an-app-and-a-space) | Grants move to `cosec:app:role:permission:<appId>:<roleId>[@<spaceId>]`, and a request sees only the roles written for its space. |
| [Path variables belong to their statement](#path-variables-belong-to-their-statement) | A condition reads only the variables its own `path` action captured; anything else is a load error. |
| [`inRole` follows roles into their space](#inrole-follows-roles-into-their-space) | A bare `inRole` value also matches `role@<request space>`; `in`, `spel` and `ognl` still see raw strings. |
| [Invalid local policy files stop startup](#invalid-local-policy-files-stop-startup) | A local policy file that cannot be parsed or built fails loading instead of being skipped. |
| [Duplicate ids and missing locations stop startup](#duplicate-ids-and-missing-locations-stop-startup) | Two files with one policy id, or a configured location that matches no file, fail loading. |
| [Rate limit rules and shadow candidates load strictly](#rate-limit-rules-and-shadow-candidates-load-strictly) | The same strictness applies to `cosec.limiter.locations` and `cosec.shadow.*`, and the errors become `IllegalStateException`. |
| [Rate limit keys are checked at load](#rate-limit-keys-are-checked-at-load) | An unsupported `key` part fails when the rule loads, not on every request. |
| [Condition parts are checked at load](#condition-parts-are-checked-at-load) | An unsupported condition `part` fails when the policy loads; a stored copy with one fails its reads. |
| [Policy ids may not start with `history:`](#policy-ids-may-not-start-with-history) | `setPolicy` rejects them, because they would share a key with the policy history. |
| [OpenAPI app ids replace `:`](#openapi-app-ids-replace) | An OpenAPI title such as `orders:api` gives the app id `orders-api`. |
| [Spring MVC authorizes the request URI](#spring-mvc-authorizes-the-request-uri) | Not `servletPath`: policies see the `DispatcherServlet` prefix, and the path is no longer percent-decoded. |
| [Path parameters are removed, and `..;` is rejected](#path-parameters-are-removed) | `/admin;x` is authorized as `/admin` on every adapter, and `/public/..;/admin` gets `400`. |
| [A regex timeout answers `401` to anonymous callers](#a-regex-timeout-answers-401-to-anonymous-callers) | It is mapped like every other deny, no longer `403` for every caller. |
| [A mismatched refresh token is an invalid token](#a-mismatched-refresh-token-is-an-invalid-token) | `refresh` throws `RefreshTokenMismatchException`, a `TokenVerificationException`, instead of `IllegalArgumentException`. |
| [No rolling upgrade](#no-rolling-upgrade) | 6.x and 7.0 cannot share a cache: stop every 6.x instance, migrate, then start 7.0. |
| [Write through CoSec, not Redis](#write-through-cosec-not-redis) | Values written with `SET` or `SADD` are not read; the cache API is CoCache 6's. |
| [Redis 7, Lettuce, and a wider ACL](#redis-7-lettuce-and-a-wider-acl) | Redis 7.0+ (or Valkey 7.2+), no Jedis, no static master-replica, and `+hkeys +hget` in the ACL. |
| [Local copies live at most 5 minutes](#local-copies-live-at-most-5-minutes) | Every local copy has a bounded lifetime, set by `expire-after-write`. |
| [CoSec creates its caches itself](#cosec-creates-its-caches-itself) | The 6.x cache component beans and five `CacheProperties` key prefixes are gone; tune caches through CoCache. |
| [Id generators are `StringIdGenerator`s](#id-generators-are-stringidgenerators) | CosId 4 generators implement only `StringIdGenerator`, which CoSec now takes. |
| [Unmigrated data stops startup](#unmigrated-data-stops-startup) | Without the schema marker `cosec:meta:schema`, CoSec data in Redis stops startup with `CoSecSchemaException`. |
| [Unmigrated data fails closed](#unmigrated-data-fails-closed) | A 6.x value fails its read with `500` and is never deleted, whatever `foreign-format` says. |
| [The global policy index is checked against the policies](#the-global-policy-index-is-checked-against-the-policies) | An indexed id without content fails with `500`; indexed content that is not `global` is ignored. |

### Upgrade checklist

Do all of this while 6.x still runs.

1. **Redis.** Check that Redis is 7.0 or later (or Valkey 7.2 or later), that the application reaches it through
   Lettuce, and that its ACL allows `+hkeys +hget` (keep `+hgetall`)
   ([details](#redis-7-lettuce-and-a-wider-acl)).
2. **Policies, app permissions and rate limit rules.** Search them, including those stored in Redis, for
   `request.path.var.` and `PATH_VARIABLES`, and fix their scope ([details](#path-variables-belong-to-their-statement)).
   Check that every local policy file loads, that policy ids are unique across files, that every configured location
   exists ([details](#duplicate-ids-and-missing-locations-stop-startup)), that every rate limit `key` and every
   condition `part` is a supported part ([details](#rate-limit-keys-are-checked-at-load),
   [details](#condition-parts-are-checked-at-load)), and that no policy id starts with `history:`
   ([details](#policy-ids-may-not-start-with-history)).
3. **Principals.** Find principals (tokens, API keys, user records) that list policy ids your repository does not
   have, and write those policies or remove the ids ([details](#missing-principal-policies-fail)).
4. **Roles.** Decide which app every 6.x role (and space) belongs to, and issue `role@space` roles in tokens and API
   keys for every non-default space ([details](#role-grants-belong-to-an-app-and-a-space)). Audit `in`, `spel` and
   `ognl` conditions over `context.principal.roles`, which see raw `role@space` strings
   ([details](#inrole-follows-roles-into-their-space)). Check that every permission id granted to a role (other than
   `*`) is defined by its app's permission catalog ([details](#missing-role-permissions-fail)).
5. **Root.** If you rely on root, enable it explicitly and issue its token in the platform tenant
   ([details](#root-is-off-platform-only-and-blacklisted)).
6. **OpenAPI.** If an OpenAPI title contains `:`, switch clients to the new `CoSec-App-Id`
   ([details](#openapi-app-ids-replace)).
7. **Paths and clients.** On Spring MVC with `spring.mvc.servlet.path` set, add the prefix to the paths of policies,
   app permissions and rate limit rules ([details](#spring-mvc-authorizes-the-request-uri)). Check conditions that
   compare `request.path` as a string ([details](#path-parameters-are-removed)), and clients that treat `401` as
   "sign in again" ([details](#a-regex-timeout-answers-401-to-anonymous-callers)).
8. **Code.** Replace direct Redis writes of app permissions and role grants with writes through CoSec
   ([details](#write-through-cosec-not-redis)), replace 6.x cache component beans with a `CacheSpecCustomizer` or
   `cocache.caches.<CacheName>.*` ([details](#cosec-creates-its-caches-itself)), change `IdGenerator`
   declarations that hold a `CosIdGenerator` to `StringIdGenerator`
   ([details](#id-generators-are-stringidgenerators)), and catch `TokenVerificationException` around a refresh
   ([details](#a-mismatched-refresh-token-is-an-invalid-token)).
9. **Plan the stop.** Prepare a one-off migration job (below), and plan a window in which no 6.x instance, no 7.0
   instance and no other writer touches CoSec's keys.
10. **Then:** stop every writer, migrate the data, verify, then start 7.0
   ([Migrating the data](#migrating-the-data)). Delete legacy role grants only after every app is migrated.

### Migrating the data

CoCache 6 cannot read the values CoSec 6.x stored with CoCache 5, and Redis is the only copy of policies, app
permissions and role grants ([Storage](./storage.md)). Two migrations convert them. Both are classes you call from a
7.0 process (an admin application or a one-off job using the starter with `cache-support`), not Actuator endpoints:
an endpoint that writes authorization data would itself have to be secured, and would stay exposed long after the
migration.

- `CoSecCoCache5Migration` converts policies, the global policy index, app permissions and revoked tokens in place
  with CoCache's [`CoCache5Migrator`](https://cocache.ahoo.me/guide/migration-6), backing up every original value
  first. The policy history (`cosec:policy:history:*`) is not CoCache data and is left alone. Revoked tokens are
  migrated whether or not the migrating process enables token revocation.
- `RolePermissionKeyMigration` copies role grants to their new app-scoped keys, one app at a time
  ([Migrating role grants](#migrating-role-grants)). The 6.x keys stay as they were.

::: danger Nothing may write during the migration
A key changed while it is migrated aborts the run, and a key 7.0 wrote before its migration is skipped **without a
backup**, so a rollback cannot restore it ([Writes before the migration](#writes-before-the-migration)).
:::

#### 1. Stop every writer

Stop all 6.x instances and anything else that writes CoSec keys, such as an administration service writing policies
or grants. Start no 7.0 instance that writes. The [schema check](#unmigrated-data-stops-startup) stops ordinary 7.0
instances from starting until the migration has finished.

#### 2. Prepare the migration job

Run the migrations from a one-off job that:

- sets `cosec.authorization.cache.migration.check-schema=false`, since the schema check would stop it from starting.
  **Never deploy that setting to the instances that serve traffic**;
- serves no traffic;
- sets `cosec.authorization.cache.migration.legacy-key-prefix` when 6.x used a key prefix other than the current one,
  `cosec.authorization.cache.migration.missing-guard-sentinel` when 6.x used a non-default
  `cocache.redis.missing-guard-sentinel`, and `cosec.authorization.cache.migration.backup-prefix` to move the backups
  away from `cocache5-backup:`.

Give the 6.x key prefix **exactly as it was configured**. 6.x did not trim a trailing `:` (`myapp:` stored policies
under `myapp::policy:`), but 7.0 does (`myapp:policy:`). With such a prefix the migration copies each value to its 7.0
key instead of converting it in place, and leaves the 6.x keys unchanged; delete them yourself once 7.0 runs well,
except `myapp::policy:history:*`, which 7.0 still uses. With a changed key prefix, the policy history is not moved: 7.0
records revisions under `<new prefix>:policy:history:*`, and the 6.x revisions stay under the old prefix.

The migration writes values with CoSec's own cache declarations, without the application's `CacheSpecCustomizer`s.
A customizer that changes a CoSec cache's codec makes the migrated values unreadable to that cache: its reads fail
(`500`), nothing is read as missing.

#### 3. Dry run, then migrate

With every writer stopped, first run a dry run, which reads and decodes every value and writes nothing, then the
migration, which writes the schema marker (`cosec:meta:schema`) last:

```kotlin
@Bean
fun migrateCoSecData(migration: CoSecCoCache5Migration) = ApplicationRunner {
    log.info { "dry run: ${migration.dryRun()}" }
    log.info { "migrated: ${migration.migrate()}" }
}
```

Read the report of each: per cache, the keys `read`, `written`, `skipped` (already migrated) and `negatives` (cached
misses, migrated as cached misses).

- A value that cannot be decoded aborts the run with a `CoCache5MigrationException` naming the key. Fix it and run
  again.
- A revoked token that expires while it is migrated aborts the run too ("the key changed"), which is likelier with
  short token lifetimes. Run it again.
- Migrated keys are skipped, so a run can be repeated.

#### 4. Migrate role grants, once per app

Run `RolePermissionKeyMigration.migrate` for every app, dry run first
([Migrating role grants](#migrating-role-grants)).

#### 5. Verify

Check the reports, then read a few policies, global policies and grants through a 7.0 process, for example with
[explain](../integration/checks.md#explaining-a-decision). A read that still fails with `ForeignFormatException` names a key
that was not migrated.

#### 6. Start 7.0

Start the 7.0 instances **without** `check-schema=false`. Local policy files are written to Redis at startup only
when their id is not there yet (`force-refresh=false`), so startup first reads the stored copy. If that copy cannot be
read, because it was not migrated or no longer passes 7.0's [path variable](#path-variables-belong-to-their-statement)
checks, startup fails ([Troubleshooting](./troubleshooting.md#startup-fails-reading-a-stored-policy)).

#### 7. Clean up

Once 7.0 runs well:

- delete the backups with `CoSecCoCache5Migration.dropBackups()`. After that, [rollback](#rollback) is no longer
  possible;
- delete the 6.x role grant keys with `RolePermissionKeyMigration.deleteLegacy()`, **only after `migrate` has run
  for every app**: it deletes the legacy grants of all apps, not only the last one migrated. Review each report's
  `skipped` and `conflicts` first, then compare the keys `deleteLegacy(dryRun = true)` lists with the ones the reports
  migrated;
- with a 6.x key prefix ending in `:`, delete the 6.x keys yourself, except `<prefix>:policy:history:*`.

### Migrating role grants

This is step 4 of [Migrating the data](#migrating-the-data). 6.x grants do not record the app they were meant for,
so CoSec cannot migrate them on its own: you name the target app, and nothing is copied to every app.
`RolePermissionKeyMigration` (a bean with `cache-support`) does the copy.

1. Decide which app each 6.x role (and space) belongs to. When roles of several apps share the old key space, split
   them with the `roleIds` argument.
2. Issue roles as `role@space` for every non-default space, in tokens and API keys.
3. Run the migration once per app from a 7.0 process against the same Redis, for example an admin application or a
   one-off job, while no instance serves traffic. Start with a dry run and read the report:

   ```kotlin
   @Bean
   fun migrateRoleGrants(migration: RolePermissionKeyMigration) = ApplicationRunner {
       val report = migration.migrate(appId = "order-app", dryRun = true) // then dryRun = false
       log.info { "migrated=${report.migrated} conflicts=${report.conflicts} skipped=${report.skipped}" }
   }
   ```

   Writes go through `RolePermissionCache`, so running instances drop their local copies. A run is idempotent: grants
   already migrated are reported as `unchanged`, and a target that holds different grants is reported as a conflict
   and kept. Keys holding a cached miss, keys that are not sets, and keys with an ambiguous role or space (more than
   one `@`, or an empty part) are reported as `skipped`.
4. Once every instance runs 7.0 and the grants of **every** app are migrated and verified, delete the old keys with
   `deleteLegacy()`. It deletes every legacy key, whichever app it was meant for, so run it last: review the `skipped`
   and `conflicts` of each report, then run `deleteLegacy(dryRun = true)` and compare the keys it lists with the ones
   the reports migrated. Until then a rollback to 6.1 still finds them.

Until a role's grants are migrated to an app, requests that rely on them are denied in that app.

- **Grants changed through 6.x after a first run** (during a rollback: there is no rolling upgrade): run the
  migration again with `overwrite = true`. It rewrites the targets whose legacy key holds other grants, but does not
  remove a grant 6.x deleted (its legacy key gone, or holding a cached miss); remove those through
  `RolePermissionCache`.
- **Lease markers.** Reading a role that has no 7.0 grants yet leaves a short-lived lease marker (about 10 seconds)
  on its new key, even on a dry run. It holds no grant and expires on its own.

### Writes before the migration

Nothing may write before the migration has finished, and the schema check stops 7.0 instances from starting until
then. A process that writes anyway, such as the migration job itself, is checked only in part:

- **Policy publishes fail, but not entirely.** Every `setPolicy` updates the global policy index, which CoCache 6 does
  not overwrite while it still holds the 6.x native SET: the publish fails with `CacheWriteException`, caused by
  `ForeignFormatException` naming the index key (`cosec:global:policy`). The index is left as it was, and the
  migration still converts it. `setPolicy` completes with this error (a local policy with `force-refresh=true` stops
  startup, see [Troubleshooting](./troubleshooting.md#publishing-a-policy-fails-before-the-migration)), and requests
  are still denied with `500`.
- **Other writes are not checked.** A global policy's content (written before the index) and app permissions replace
  the 6.x value of their key in the 7.0 format. The migration then skips that key and **keeps no backup of it**, so a
  rollback cannot restore its 6.x value.
- **Role grants** go to new keys, and one written before its migration is reported there as a conflict.

### Rollback

Before the backups are dropped, 6.1 can be restored:

1. Stop every 7.0 instance and every writer.
2. Call `CoSecCoCache5Migration.rollback()`. It refuses while keys without a backup exist (below).
3. Start 6.1 **without traffic**.
4. Write again through 6.1 what 7.0 created (below).
5. Only then let 6.1 serve traffic.

Rollback removes the schema marker first, so a 7.0 instance started on the restored data refuses to start, and
restores the snapshot taken by the migration:

- **Migrated keys** (policies, app permissions, revoked tokens, the global policy index) get their 6.x value back from
  their backup: type, content and remaining lifetime. Changes 7.0 made to them are lost, and a policy 7.0 made global
  leaves the index.
- **Keys without a backup** are the keys 7.0 created after the migration, such as new policies, app permissions or
  revocations, and keys it wrote [before the migration](#writes-before-the-migration). They stay in the 7.0 format.
  6.1 does not fail on them: CoCache 5 reads a value it cannot decode as missing and **overwrites it with a cached
  miss**. A deny policy created by 7.0 then silently stops applying, and a token revoked by 7.0 is accepted again.

  ::: danger Keys without a backup
  `rollback()` lists these keys (`keysWithoutBackup()`) and refuses with `KeysWithoutBackupException` while there are
  any. Review them, call `rollback(allowKeysWithoutBackup = true)`, then write those policies and app permissions
  again and revoke those tokens again through 6.1, before it serves traffic.
  :::

- **A 6.x key prefix ending in `:`.** The copies 7.0 used are deleted and 6.1 reads its own keys, which were never
  changed. Keys 7.0 created under its own prefix are invisible to 6.1, so they too must be written again through 6.1.
- **Role grants.** The 6.x role grant keys were never changed, unless `deleteLegacy()` ran, so grants added or
  removed in 7.0 must be made again through 6.1.
- **Policy history** is not rolled back: revisions 7.0 recorded stay in it, so restoring the latest revision of a
  policy through 6.1 may bring back what 7.0 wrote. Check a revision's content before restoring it.

### Common questions

**Can 6.x and 7.0 instances run side by side?** No. They cannot read each other's values or invalidations
([No rolling upgrade](#no-rolling-upgrade)).

**7.0 refuses to start with `CoSecSchemaException`.** The data has not been migrated, or was rolled back
([Troubleshooting](./troubleshooting.md#startup-fails-with-cosecschemaexception)).

**Every request gets `500` after the upgrade.** Some data was not migrated, or a global policy has no content
([Troubleshooting](./troubleshooting.md#every-request-fails-with-500)).

**Role-based requests are denied after the upgrade.** Their grants were not migrated to the request's app, or the
token's roles are not written for the request's space ([Migrating role grants](#migrating-role-grants)).

**Can I set `cocache.defaults.foreign-format=reclaim` to get rid of old values?** It has no effect on CoSec's caches,
and must not: with no loader, a reclaim would delete the only copy ([Unmigrated data fails closed](#unmigrated-data-fails-closed)).

**Can I write the schema marker by hand?** Not while 6.x data may exist. The marker tells 7.0 that every value is
in the 7.0 layout; data under a 6.x key it does not read would be read as missing.

**Can I run `deleteLegacy()` after migrating one app?** No: it deletes the legacy grants of every app. Run it last
([Clean up](#_7-clean-up)).

**I already ran 7.0 against this Redis in a test environment.** `migrate()` skips what is already migrated and
writes the marker ([Troubleshooting](./troubleshooting.md#startup-fails-with-cosecschemaexception)).

### Authorization and loading changes

What each authorization and loading row of the [table](#breaking-changes) means, and what to do.

#### Missing principal policies fail

When a principal lists a policy id that the `PolicyRepository` does not return (deleted, never written, lost from
Redis), `SimpleAuthorization` now fails with `MissingPolicyException`, which the pipeline maps to `500`. Before, the
id was skipped and the remaining policies decided: when the missing policy was a deny, such as one that suspends an
account, a global allow let the request through. `AuthorizationChecker` answers `false` for such a principal, and an
explanation reports the `500` with a `MISSING_POLICY` warning.

Before upgrading, find principals (tokens, API keys, user records) that list policy ids your repository does not
have, and either write those policies or remove the ids. `PolicyRepository.getPolicies` itself is unchanged: it
still returns the policies it found, so custom repositories need no change.

#### Missing role permissions fail

When a role is granted a permission id that its app's permission catalog does not define (removed from the
catalog, or mistyped), `SimpleAuthorization` now fails with `MissingPermissionException`, which the pipeline maps to
`500`. Before, the id was skipped: when it named a deny, a global or principal allow let the request through. A
request of a principal holding that role in that app and space fails whenever the role layer is evaluated and no
deny matched first: when the app's condition does not match, the layer does not apply, and a request that a deny (in
an earlier layer, or earlier in the role layer) already matches stays denied. `*` still grants every permission.
`AuthorizationChecker` answers `false`, an explanation reports the `500` with a `MISSING_PERMISSION` warning, and a
shadow candidate app permission that drops a granted id reports `CANDIDATE_ERROR`. `AccessAnalyzer` and
`PolicyTestKit` reject such grants when built or run; a test suite's `roles` are granted in every app, so each app
must define them (use `appRoles` for ids of one app). The default `AppRolePermission.rolePermissionIndexer` throws
the same exception.

Before upgrading, compare each role grant with its app's permission catalog, and either add the missing permission
or remove the id from the grant ([Granting permissions to roles](../policies/role-permissions.md#granting-permissions-to-roles)).

#### Root is off, platform-only, and blacklisted

Before, any principal whose id was `cosec` (or the `-Dcosec.root` system property) bypassed every check, including
the blacklist, and `cosec.authorization.root-enabled` defaulted to `true`. A user tenant whose identity provider let
a user pick the id `cosec` handed that user platform-wide access. Now:

- `cosec.authorization.root-enabled` defaults to `false`: no principal is root unless you enable it.
- When enabled, `RootPrincipalMatcher.DEFAULT` treats a principal as root only if its id is the root id **and** its
  tenant is the platform tenant (`(platform)`, `Tenant.PLATFORM_TENANT_ID`). The root id in a user tenant, in the
  default tenant, or on a principal without a tenant is an ordinary principal.
- The blacklist is checked before root, so a blacklisted request from root is denied. In an explanation the
  `BLACKLIST` layer now comes before `ROOT`, and `ExplainLayer` lists them in that order.
- Code that builds `SimpleAuthorization`, `AuthorizationExplainer` or `ShadowAuthorization` directly defaults to
  `RootPrincipalMatcher.NONE` too, like the starter and `PolicyTestKit`: without a `rootPrincipalMatcher` argument, no
  principal is root.

If you rely on root, set `cosec.authorization.root-enabled=true` and issue the root principal's token with `tenantId`
`(platform)`. Code that builds these classes directly passes `rootPrincipalMatcher = RootPrincipalMatcher.DEFAULT`,
and `PolicyTestKit` takes it as the matcher argument of `run`. If root must work some other way, provide your own
`RootPrincipalMatcher` bean.

#### Role grants belong to an app and a space

In 6.x a role grant was stored per role and space, without the app, and the client chose both the app
(`CoSec-App-Id`) and the space (`CoSec-Space-Id`). A grant, `*` included, therefore applied in every app that defined
the same permission ids, and changing `CoSec-Space-Id` picked up the grants of a same-named role in another space. In
7.0:

- Grants are keyed by app, role and space. The Redis key moves from `cosec:role:permission:<roleId>[@<spaceId>]` to
  `cosec:app:role:permission:<appId>:<roleId>[@<spaceId>]`, and `RolePermissionCache` takes an
  `AppSpacedRoleId(appId, roleId, spaceId)` instead of a `SpacedRoleId`. App ids must not contain `:` and role ids
  must not contain `@`; the OpenAPI app permission generator replaces `:` in the title.
- A request with `CoSec-Space-Id: x` is evaluated with only the principal's roles written `role@x`; without the
  header, with only the roles without a suffix. Issue `role@space` in tokens and API keys for every space a principal
  holds a role in, after checking membership ([Roles and spaces](../integration/authentication.md#roles-and-spaces)).
- Authorization never reads 6.x keys. Until they are migrated, roles have no grants and role-based requests are
  denied (fail closed) ([Migrating role grants](#migrating-role-grants)).
- `AppRolePermissionRepository.getAppRolePermission` keeps its signature. `SimpleAuthorization` now passes the roles
  that apply in the space, without the `@space` suffix; a custom repository must return only the grants made for that
  app and space.
- The explainer adds the `NO_ROLE_IN_SPACE` warning.
- `AccessAnalyzer` and `PolicyTestKit` scope role grants the same way. A grant role written without a suffix is
  granted in the default space only; write `role@space` for grants in another space. `whoCan` takes grants from the
  request's space, `grantsOf` reaches a grant only through a role held in its space and takes an optional `spaceId`,
  and `AccessRule` adds `spaceId`. Test suites add `appRoles` for grants made in one app; `roles` still grants in
  every app. Malformed roles now fail instead of never matching.

The model itself is described in [Role grant scope](../policies/role-permissions.md#role-grant-scope).

#### Path variables belong to their statement

Before, `request.path.var.*` read whatever the last matching `path` action of the request had captured, in any
statement, policy or layer. A condition could therefore depend on another statement: a deny statement that never
matched could make an `all`-action allow match (adding a deny widened access), a policy condition read the variables
of the global layer, and a rate limit rule without a `path` action read the variables of a policy. Now:

- the variables are cleared before every statement, permission and rate limit rule matches its action, and before
  policy and app permission conditions, so a condition only sees what its own `path` action captured;
- reading them where no `path` action of the same statement or rule captured them is a load error,
  `PathVariableScopeException`: in a policy or app permission `condition`, in the condition of a statement or
  permission whose action is not `path`, in the `condition` or `key` of a rate limit rule whose action is not `path`,
  and in any action's configuration, such as a `path` template `/orders/#{...PATH_VARIABLES...}`: a template is
  evaluated before its action matches, when the variables are not its own
  ([Path variables](../policies/language.md#path-variables)).

Parsing such a policy, app permission or rate limit rule fails, and so does constructing `PolicyData`,
`AppPermissionData` or `RateLimitRule` with one. `RedisPolicyRepository.setPolicy` rejects it. A local policy file
with one stops the application from starting. A policy already stored in Redis with one fails its reads, which denies
requests with `500`.

Before upgrading, search policies, app permissions and rate limit rules for `request.path.var.` and
`PATH_VARIABLES`. Move each reference into a statement whose `path` action captures the variable, for example replace
`"action": "*"` with the pattern that declares it, and move a policy-level condition into the conditions of its
statements.

#### `inRole` follows roles into their space {#inrole-follows-roles-into-their-space}

With roles issued as `role@space`, a bare `inRole` value now also matches the principal's `role@<request space>`, so
a deny such as `inRole: suspended` keeps matching after tokens switch from `suspended` to `suspended@team-a`. A role
held without a suffix still matches in every space. A value written `role@space` is compared literally with the
principal's roles, as before. Conditions that read `context.principal.roles` some other way (`in`, `spel`, `ognl`)
still see the raw strings, `suspended@team-a` included, and stop matching a bare role name once tokens carry
`role@space` ([Matching roles](../policies/language.md#matching-roles)).

#### Invalid local policy files stop startup

`LocalPolicyLoader` used to log and skip a policy file it could not parse or build (malformed JSON, an unknown matcher
type, an invalid option), which could silently drop a deny. Now any such file fails loading, as rate limit rule files
already did, and the error names the file
([Troubleshooting](./troubleshooting.md#startup-fails-loading-a-policy-file)).

#### Duplicate ids and missing locations stop startup

`LocalPolicyLoader` used to keep one of two files that declare the same policy id and silently drop the other, and
only logged a warning for a location in `cosec.authorization.local-policy.locations` that does not exist or matches no
file. Either could drop a deny without notice, for example after a typo in a location that only holds denies. Now
both fail loading, naming the id or the location. The default location `classpath:cosec-policy/*-policy.json` may
still match nothing, so applications without local policies are unaffected. Before upgrading, check that every
configured location exists and that policy ids are unique across files; the same file reached through two locations
is loaded once.

#### Rate limit rules and shadow candidates load strictly

`LocalRateLimitRuleLoader` and `LocalShadowPolicyProvider` used to load nothing from a location that does not exist or
matches no file, so a typo in `cosec.limiter.locations` silently removed every quota it held, and a typo in
`cosec.shadow.locations` or `cosec.shadow.app-permission-locations` made shadow mode report no difference for a change
it never evaluated. Now:

- such a location stops startup (`Rate limit rule location [...] matches no file`, `Shadow policy location [...]`,
  `Shadow app permission location [...]`); only the defaults (`classpath:cosec-rate-limit/*-rate-limit.json`,
  `classpath:cosec-shadow/*-policy.json`, `classpath:cosec-shadow/*-app-permission.json`) may match nothing;
- a rule id, shadow policy id or shadow app permission id declared twice fails with
  `... id [<id>] is declared more than once`, naming the files, and is now an `IllegalStateException` (it was an
  `IllegalArgumentException`);
- the same file reached through two locations is loaded once; before, a rule or candidate file matched by two
  overlapping locations failed as a duplicate;
- `PolicyTestKit` and `AccessAnalyzer.load` (already strict about missing locations) now throw
  `IllegalStateException` instead of `IllegalArgumentException` for them, and also fail on a policy or app permission
  id declared by two files;
- the duplicate local policy error now reads `Policy id [<id>] is declared more than once`.

#### Rate limit keys are checked at load

A `key` that is not a supported part (for example `request.ip` instead of `request.remoteIp`) used to load and then
fail every matching request with `500`, after earlier rules had spent their permits. Constructing or loading such a
rule now fails ([Keys](../policies/rate-limiting.md#keys)).

#### Condition parts are checked at load

A value condition (`eq`, `in`, `contains`, `startsWith`, `endsWith`, `regular`, `path`) whose `part` is not a
supported part (for example `request.ip` instead of `request.remoteIp`, or `request.headers.x` instead of
`request.header.x`) used to load and then fail every matching request with `500`. Building such a matcher now fails
with `Condition [<type>] part [<part>] is not a supported part`, as rate limit keys already did: a local policy file
with one stops startup, `setPolicy` rejects it, and a policy stored in Redis with one fails its reads, which denies
every request that reads the policy with `500`, not only those its statement matches. Before upgrading, search
policies, app permissions and rate limit rule conditions for `"part"` and check each value against the
[supported parts](../policies/language.md#parts).

#### Policy ids may not start with `history:` {#policy-ids-may-not-start-with-history}

The policy history stores the revisions of policy `<id>` under `<prefix>:policy:history:<id>` (and `<id>:seq`),
inside the namespace of the policies themselves, so a policy with the id `history:<id>` would share a key with the
history of policy `<id>` ([Redis keys](./storage.md#redis-keys)). `RedisPolicyRepository.setPolicy` now rejects such
ids with `Policy id [...] must not start with [history:]`, and so does startup when a local policy with one is written
to Redis. Ids such as `history` or `history-viewer` are unaffected. Rename any policy whose id starts with `history:`
before upgrading; the 6.x migration skips such keys as policy history.

#### OpenAPI app ids replace `:` {#openapi-app-ids-replace}

`OpenAPIAppPermissionGenerator` derives the app id from the OpenAPI title, and app ids may no longer contain `:`. An
app whose title contains `:` (for example `orders:api`) gets the id `orders-api`; clients must send the new id in
`CoSec-App-Id`, and its role grants must be written for it.

### Request and token changes

What each request and token row of the [table](#breaking-changes) means, and what to do.

#### Spring MVC authorizes the request URI

The WebMvc adapter used `HttpServletRequest.servletPath`, which drops `pathInfo`. With the `DispatcherServlet` mapped
under a prefix (`spring.mvc.servlet.path=/api`), every request was authorized as `/api`: a deny on `/api/admin/**`
never matched, and an allow on `/api/**` let every request through. The path is now the request URI without the
context path, as Spring MVC routes it and as WebFlux sees it ([The request path](../integration/web.md#the-request-path)).
With the default `/` mapping the matched path is the same as before, except that it is no longer percent-decoded:
`path` actions decode segments as before, but a condition that compares `request.path` as a string (`eq`, `regular`,
…) now sees `/a%20b`, not `/a b`. Before upgrading an application with a servlet path, add the prefix to its
policies, app permissions and rate limit rules.

#### Path parameters are removed, and `..;` is rejected {#path-parameters-are-removed}

On every adapter, `/admin;jsessionid=1` is authorized as `/admin`, as Spring routes it; before, WebFlux and Gateway
passed it through, so a `request.path` condition could miss it. A `.` or `..` segment followed by a path parameter
(`/public/..;/admin`), which servlet containers resolve as `..`, now answers `400` like any other traversal.

#### A regex timeout answers `401` to anonymous callers {#a-regex-timeout-answers-401-to-anonymous-callers}

A `regular` condition over its time budget used to answer `403` whatever the caller. It is now mapped like every
other deny: `401` for an anonymous caller (with `Token Expired` or `Token Invalid` when its token failed
verification), `403` for an authenticated one ([Responses and status codes](../integration/web.md#responses-and-status-codes)).
Clients that treat `401` as "sign in again" now get it for these requests too.

#### A mismatched refresh token is an invalid token

`JwtTokenVerifier.refresh` threw `IllegalArgumentException` (`Illegal refreshToken.`) when the refresh token was not
issued with the access token, so the mismatch surfaced as an unexpected error. It now throws
`RefreshTokenMismatchException`, a `TokenVerificationException`, like every other token failure
([Refreshing tokens](../integration/authentication.md#refreshing-tokens)). Code that caught
`IllegalArgumentException` around a refresh catches `TokenVerificationException`.

### CoCache 6 and CosId 4

CoSec 7.0 runs on [CoCache 6.6](https://cocache.ahoo.me/guide/migration-6) and
[CosId 4.0](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md). CoCache 6 changed how values are
stored in Redis, so this upgrade needs a short stop and a one-off data migration ([Migrating the data](#migrating-the-data)).

#### No rolling upgrade

CoCache 6 changed the Redis value layout and the invalidation channel, so 6.x and 7.0 instances cannot share a cache:
neither sees the other's writes. Stop every 6.x instance, migrate the data, then start 7.0.

The Redis keys of policies (`cosec:policy:<id>`), the global policy index (`cosec:global:policy`), app permissions
(`cosec:app:permission:<appId>`) and revoked tokens (`cosec:token:revoked:<tokenId>`) stay the same, where `cosec` is
[`cosec.authorization.cache.key-prefix`](./configuration.md#cache); only their values change format. Role grants move
to app-scoped keys ([above](#role-grants-belong-to-an-app-and-a-space)). Policy history, API keys and rate limit
counters are not CoCache data and keep their keys and formats. Every key is listed in
[Storage](./storage.md#redis-keys).

#### Write through CoSec, not Redis

Every CoCache 6 value starts with a binary header, so data written with `SET` or `SADD` (for example app permissions
and role grants written by an administration service) is not read. Write policies with `PolicyRepository.setPolicy`,
and app permissions and role grants through the `AppPermissionCache` and `RolePermissionCache` beans. Their API is
CoCache 6's:

| 6.x (CoCache 5) | 7.0 (CoCache 6) |
|---|---|
| `me.ahoo.cache.api.Cache` | `me.ahoo.cocache.api.Cache` |
| `cache[key] = value`, `set(key, ttlAt, value)` | `put(key, value)`, `put(key, value, ttl: Duration)` |
| `evict(key)` | `invalidate(key)` |
| `GlobalPolicyIndexCache` is a `SetElementsCache`: `addElements` / `removeElements` | it is a `SetCache`: `add` / `remove` |

#### Redis 7, Lettuce, and a wider ACL

CoCache 6 needs Redis 7.0 or later (or Valkey 7.2 or later) and builds its own Lettuce client from
`spring.data.redis.*`: Jedis is not supported, and a static master-replica setup fails at startup. A minimal Redis ACL
must also allow `+hkeys +hget` (keep `+hgetall`) ([Redis requirements](./storage.md#redis-requirements)).

#### Local copies live at most 5 minutes

CoCache 6 always bounds how long a local copy lives (`localMaxAge`, 5 minutes by default) and then reads Redis again;
it has no unlimited setting. In 6.x a local copy of a policy or permission lived until a change invalidated it.
`expire-after-write` of `cosec.authorization.cache.policy`, `role` and `token` now sets this bound, so it applies to
every local copy ([Local cache settings](./configuration.md#local-cache-settings)).

#### CoSec creates its caches itself

The caches are no longer declared with `@EnableCoCache`, and the 6.x component beans (`PolicyCache.KeyConverter`,
`PolicyCache.ClientSideCache`, `PolicyCache.DistributedCache`, and the same for the other caches) are gone. Tune a
cache with a CoCache `CacheSpecCustomizer` bean or `cocache.caches.<CacheName>.*`, where the cache names are
`PolicyCache`, `GlobalPolicyIndexCache`, `AppPermissionCache`, `RolePermissionCache` and `RevokedTokenCache`. Two
settings stay CoSec's: the storage layout (`key-prefix` there is ignored) and, for policies and permissions, "never
expire, and fail when Redis cannot be read", which is applied after any customizer or property
([Precedence](./configuration.md#precedence)).

`CacheProperties` lost `globalPolicyIndexKey`, `policyKeyPrefix`, `appPermissionKeyPrefix`, `rolePermissionKeyPrefix`
and `revokedTokenKeyPrefix`. CoCache itself renamed or removed several `cocache.*` properties (for example
`cocache.redis.strict-failure`); a removed one fails startup and names its replacement.

#### Id generators are `StringIdGenerator`s {#id-generators-are-stringidgenerators}

In CosId 4, `UuidGenerator` and `CosIdGenerator` implement only `StringIdGenerator`. `AuditingAuthorization`,
`JwtTokenConverter`, `JustAuthProvider`, `ReactiveRequestParser` and `ServletRequestParser` take a
`StringIdGenerator`, and the starter injects one: a `CosIdGenerator` or other id generator bean is still used when
present, `UuidGenerator` otherwise. Code that passes an `IdGenerator` keeps compiling; code that declares an
`IdGenerator` to hold a `CosIdGenerator` must declare a `StringIdGenerator`. If the application uses CosId itself,
follow CosId's [migration guide](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md).

#### Unmigrated data stops startup

7.0 does not always read 6.x data where 6.x stored it: with a key prefix ending in `:` (6.x kept `myapp::policy:*`,
7.0 reads `myapp:policy:*`), with a changed key prefix, or after the rollback of such a migration, 7.0 would find no
policy, no global `DENY` and no revocation at all, and read them as missing. So the starter checks the schema marker
`cosec:meta:schema` before it creates any CoSec cache. Without the marker, CoSec data under the 6.x key prefix
(`legacy-key-prefix`, as configured) or under the current one stops startup with `CoSecSchemaException`; the
migration writes the marker when it finishes. Without the marker and without any CoSec data, the deployment is new
and the marker is written. The check cannot find 6.x data under a prefix it is not told about: when the key prefix
changes, set `cosec.authorization.cache.migration.legacy-key-prefix` ([Schema marker](./storage.md#schema-marker)).

#### Unmigrated data fails closed

A 7.0 instance that reads a value CoSec 6.x wrote fails the read (`CacheReadException`, caused by CoCache's
`ForeignFormatException`) and leaves the value in Redis, so the request is denied with `500`. Every CoSec cache keeps
this behavior, even with `cocache.defaults.foreign-format=reclaim`: with no loader, a reclaim would delete the only
copy of a policy or permission, and a 6.x revocation, which would make its token valid again.

#### The global policy index is checked against the policies

The index and the policy contents are separate Redis keys. In 6.x an indexed id without content was skipped, so a
lost global `DENY` silently stopped applying; 7.0 fails the read with `MissingPolicyException` and the request is
denied with `500` ([Troubleshooting](./troubleshooting.md#every-request-fails-with-500)). An indexed id whose content
is not of type `global` (a concurrent or half-finished republish) is ignored instead of applying to everyone
([Global policy index](./storage.md#the-global-policy-index)).

## 6.0 to 6.1

### Breaking changes {#breaking-changes-1}

**Rate limits are rules, not policy conditions.** The `rateLimiter`, `groupedRateLimiter`, `redisRateLimiter` and
`redisGroupedRateLimiter` conditions are removed, together with `TooManyRequestsException`. A policy that still uses
one no longer parses: local policy files that use one were skipped with an `ERROR` log in 6.1 (since 7.0 they stop
startup), and policies stored in Redis that use one fail their reads, which denies requests. Move each limit into a
[rate limit rule](../policies/rate-limiting.md#rules) before upgrading:

| Before (policy statement) | After (rate limit rule) |
|---|---|
| The statement's `action` | The rule's `action` |
| The statement's other conditions | The rule's `condition` |
| `part` | `key` |
| `permitsPerSecond`, `windowSeconds`, `strictFailure` | The same fields |
| `rateLimiter` / `groupedRateLimiter` | Rules run on `LocalRateLimiter` when Redis is absent |
| `expireAfterAccessSecond` | Not needed: idle buckets are dropped after an hour |

Three behaviors change with it:

- **Denied requests no longer spend permits.** Before, a limit in a policy condition also counted requests that
  another layer then denied.
- **Quotas belong to the rule `id`.** Before, Redis limiters with identical settings shared one quota.
- **Counters restart.** Redis counters move from `<key-prefix>:<settings hash>:…` to `<key-prefix>:<id>:…`, so the
  old counters are abandoned and expire on their own. During a rolling upgrade, old and new instances count
  separately.

**Responses can carry rate limit headers.** When a rule matches, responses include `RateLimit-Limit`,
`RateLimit-Remaining` and `RateLimit-Reset`, and `429` responses include `Retry-After`
([Responses and headers](../policies/rate-limiting.md#responses-and-headers)).

**The `Authorization` bean is decorated.** With rate limiting enabled (`cosec.limiter.enabled`, default `true`) the
auto-configured bean is a `RateLimitingAuthorization` around `SimpleAuthorization`, and with `micrometer-support` a
`MeteredAuthorization` around that. Code that casts the bean to `SimpleAuthorization` must inject it as
`Authorization`, or unwrap `Delegated.delegate`.

**Decision types gained a field.** `AuthorizationDecision` and `SecurityDecision` have a new `rateLimit` property.
Kotlin code that destructures them or calls `copy` by position must be recompiled.

### New features

Everything below is additive. Features that change what a request experiences are off by default.

| Feature | Default | Where |
|---|---|---|
| Rate limit rules: per-IP, per-user or shared quotas, counted in Redis or in memory | on, once a rule file exists | [Rate limiting](../policies/rate-limiting.md) |
| Explain a decision, for a described principal or a real token or API key | off (`cosec.explain.enabled`) | [Explaining a decision](../integration/checks.md#explaining-a-decision) |
| Shadow mode: try candidate policies and app permissions on real traffic | off (`cosec.shadow.enabled`) | [Shadow mode](../policies/testing.md#trying-changes-in-shadow-mode) |
| `AuthorizationChecker`: check permissions in code, one action or many | always available | [Checking a permission](../integration/checks.md#checking-a-permission) |
| Object-level rules with `resource.<name>` parts | opt in per policy | [Object-level rules](../integration/checks.md#object-level-rules) |
| `time` condition: validity periods and daily windows, self-expiring grants | opt in per policy | [Time conditions](../policies/language.md#time-conditions) |
| Policy history and rollback | off (`cosec.authorization.cache.history.enabled`) | [Policy history](./storage.md#policy-history-and-rollback) |
| Declarative policy test cases (`PolicyTestKit`) | library | [Policy test cases](../policies/testing.md#policy-test-cases) |
| Access reviews (`AccessAnalyzer`): who can do what | library | [Access reviews](../policies/testing.md#access-reviews) |
| API keys for machine clients, in memory or in Redis | off (`cosec.api-key.enabled`) | [API keys](../integration/authentication.md#api-keys) |
| `cosec.authorization` decision metrics | on with `micrometer-support` and a `MeterRegistry` | [Observability](./observability.md#metrics) |

New properties: `cosec.limiter.locations`, `cosec.explain.enabled`, `cosec.shadow.*`, `cosec.metrics.enabled`,
`cosec.api-key.*` and `cosec.authorization.cache.history.*` ([Configuration](./configuration.md)).

### Upgrade checklist {#upgrade-checklist-1}

1. Search your policies, including those stored in Redis, for the four removed condition types, and write the
   equivalent rate limit rules under `classpath:cosec-rate-limit/`.
2. Replace any `TooManyRequestsException` thrown by custom matchers with a rate limit rule.
3. Check that clients and proxies tolerate the new rate limit headers.
4. Replace casts of the `Authorization` bean to `SimpleAuthorization`.
5. Upgrade, then confirm in the startup log that every rate limit rule file loaded. An unreadable rule file stops
   startup.
6. Optionally, add `PolicyTestKit` suites for the decisions you rely on before you change policies again.

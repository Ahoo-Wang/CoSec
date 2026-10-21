---
name: cosec-troubleshoot
description: Diagnose CoSec authentication and authorization failures, including unexpected 401/403/429 responses, policy loading, JWT rejection, matcher mismatches, rate limit rules, and unexplained access decisions. Do not change behavior unless the user asks for a fix.
---

# CoSec Troubleshooting

The human-facing guide is https://cosec.ahoo.me/operations/troubleshooting; this skill adds the agent workflow.

Find the first incorrect transition from request parsing to authentication, policy loading, matching, and response mapping. Gather evidence before proposing a policy or code change.

## Start with observable facts

Capture the request method/path, response status and CoSec JSON reason, principal ID/authenticated state, app/space/tenant IDs, effective configuration, loaded policy IDs, and relevant startup/request logs. Redact tokens and secrets.

Enable focused logging:

```yaml
logging:
  level:
    me.ahoo.cosec.authorization.SimpleAuthorization: DEBUG
    me.ahoo.cosec.audit: DEBUG
```

`SimpleAuthorization` identifies the matched policy/statement or role/permission. When `AuditingAuthorization` is active, the default sink emits structured deny events at WARN and allow events at DEBUG, including principal, request, decision, and matched rule when available. A custom `Authorization` bean is not wrapped automatically.

When `cosec.explain.enabled=true`, prefer explaining the decision over reading logs: inject `AuthorizationExplainer` (or call `POST /actuator/cosecExplain` with `principal` and `request` objects) to get the real status, the deciding rule (omit `principal` and pass the caller's `Authorization` token or `X-API-Key` in `request.headers` to explain a real credential; rejected credentials add a `TOKEN_INVALID` warning), every statement's and permission's outcome (`MATCHED`, `ACTION_MISS`, `CONDITION_MISS`, `NOT_EVALUATED`, `ERROR`), overridden allows, matching rate limit rules, and warnings such as `MISSING_POLICY`, `NO_APP_PERMISSION` or `NO_ROLE_IN_SPACE` (the principal has roles, but none written for the request's space). It spends no rate-limit permits. Treat `TRACE_INCONSISTENT` as a CoSec bug and trust the reported decision.

## Interpret the status first

| HTTP status | CoSec meaning |
|---|---|
| 400 | Invalid normalized request path |
| 401 | Authorization denied (or a regex matcher timed out) while the parsed principal is anonymous; invalid/expired tokens also fall back to anonymous and preserve the token reason |
| 403 | An authenticated principal was denied, or a regex matcher timed out for one and failed closed |
| 429 | An allowed request exceeded a rate limit rule (`RateLimitingAuthorization`) |
| 500 | Unexpected authorization error, including Redis being unavailable for policy/permission reads (those caches fail closed) a principal listing a policy id the repository does not have, and a global policy index entry without content (both `MissingPolicyException`); inspect the server exception |

The authorization result alone does not choose 401 versus 403; the filter maps a denied anonymous principal to 401 and a denied authenticated principal to 403.

## Decision order

`SimpleAuthorization` evaluates:

1. Blacklist → explicit deny (applies to root too).
2. Root principal → allow, only when `cosec.authorization.root-enabled=true` (default `false`) and the principal is in the platform tenant (`(platform)`) with the root ID (`cosec` by default, overridden by JVM system property `cosec.root`), unless a custom `RootPrincipalMatcher` bean decides otherwise.
3. Global policies.
4. Principal-attached policies.
5. Role permissions: only the principal's roles in `request.spaceId` (`role@space`; unsuffixed roles only in the default space), with their grants in `request.appId`.
6. No match → implicit deny.

Each policy tier evaluates matched policy-level conditions once, pools all statements, and checks every deny before any allow. Tiers are combined deny-first (CoSec 6+): an explicit deny in any tier wins over an allow from any other tier, so a principal or role deny overrides a global allow. Only if no tier denies does a matched allow grant access.

## Symptom guide

### Unexpected 401

- Check whether the request actually carried a bearer token and whether parsing produced an authenticated principal. Tokens are read from the `Authorization` header or `authorization` cookie; the `authorization` query parameter is ignored unless `cosec.authorization.allow-query-token=true` (default `false` since CoSec 6).
- Match `cosec.jwt.algorithm` and secret to the issuer; only HMAC256/384/512 are supported.
- Check expiration and the response reason (`Token Expired` versus `Token Invalid`).
- If revocation is enabled, verify the cache capability supplied a real Redis-backed `TokenStore`; otherwise the starter warns that logout is ineffective.
- If the token is valid but the request remains anonymous, inspect the active `SecurityContextParser` and transport filter.

### Unexpected 403 or missing allow

- Read the debug/audit match. An explicit deny is a matched deny; an implicit deny means no rule in any tier matched.
- Confirm a global policy loaded and its policy-level condition matched.
- Check the action's HTTP method, path, trailing slash, and captured variables.
- Check condition parts and the actual principal roles/attributes/tenant.
- Remember that `inTenant.value` is `default`, `user`, or `platform`, not a tenant ID.
- For role permissions (CoSec 7+): grants are keyed by app, role and space (`<prefix>:app:role:permission:<appId>:<roleId>[@<spaceId>]`) and apply only in their own app. With `CoSec-Space-Id: x`, only token roles written `role@x` count; without it, only unsuffixed roles. A grant under the 6.x key `<prefix>:role:permission:<roleId>` is never read: migrate it to its app with `RolePermissionKeyMigration.migrate(appId)` (see the upgrading guide).

### Request allowed unexpectedly

- Follow the decision order: the blacklist runs first (had it matched, the request would be denied), then root. Check whether `cosec.authorization.root-enabled=true` and the principal is the platform tenant's root id; root is off by default.
- Find which tier matched an allow (the debug log and audit event name the policy statement or role permission). If you expected a deny, check that the deny's action and condition both match: a matched deny in any tier would have won.
- Verify the deny action and condition both match; array order cannot make an allow beat a same-tier deny.
- Confirm the intended policy was loaded (local files no longer get skipped, and duplicate IDs fail startup, in 7.0; Redis-stored policies are what authorization reads).

### Policy file has no effect

- Confirm `cosec.authorization.local-policy.enabled=true` and the file matches `local-policy.locations`.
- Since 7.0, `LocalPolicyLoader` fails startup with `Failed to load policy [<file>]` on any malformed JSON, missing required field, unknown matcher type, or constructor-time configuration error (before 7.0 it logged and skipped the file, which could drop a deny). It also fails startup with `Policy id [<id>] is declared more than once` (before 7.0 one copy was silently dropped) and with `Policy location [<location>] matches no file` for a configured location that does not exist or matches nothing, usually a path typo (before 7.0 only a warning); only the default `classpath:cosec-policy/*-policy.json` may match nothing.
- Since 7.0 rate limit rule files (`cosec.limiter.locations`) and shadow candidates (`cosec.shadow.locations`, `cosec.shadow.app-permission-locations`) fail startup the same way: `Rate limit rule location [<location>] matches no file`, `Shadow policy location [...]`, `Shadow app permission location [...]` for a configured location that does not exist or matches nothing (before 7.0 it loaded nothing, silently, dropping every quota or candidate it held), and `... id [<id>] is declared more than once` for a duplicate id. Only the defaults `classpath:cosec-rate-limit/*-rate-limit.json`, `classpath:cosec-shadow/*-policy.json` and `classpath:cosec-shadow/*-app-permission.json` may match nothing.
- Deferred matcher errors still load. For example, an unsupported `part` throws during authorization and the transport returns 500; reproduce it with `DefaultPolicyEvaluator` or the failing request.
- Local policy files are deduplicated by file after location expansion (a file matched by two locations loads once); two files with the same ID fail startup. Assert that the expected ID and content loaded.
- `LocalPolicyLoader` does not serve authorization decisions directly. Enable `init-repository` or prepopulate `PolicyRepository`; use `force-refresh` only when overwriting repository state is intended.

### Policies or permissions missing after upgrading to 7.0

- CoCache 6 cannot read what CoSec 6.x (CoCache 5) stored in Redis. Until the data is migrated, every read of it fails (`CacheReadException` caused by `ForeignFormatException`) and requests get 500. Migrate with `CoSecCoCache5Migration` (policies, global index, app permissions, revoked tokens) and `RolePermissionKeyMigration` (role grants, per app); see the upgrading guide, "6.1 to 7.0".
- Startup fails while initializing local policies (`init-repository` with `force-refresh=false`, the default): the initializer reads each id's stored copy first, so an unmigrated copy (`ForeignFormatException`) or a migrated one that now violates path variable scope (`PathVariableScopeException`) stops startup even when the local file is valid. Migrate or fix the stored policy; after the migration, starting once with `force-refresh=true` overwrites the stored copies without reading them. Warn that `force-refresh=true` also overwrites every change administrators made to those policies through the repository, and that it fails before the migration (every publish does); turn it off again afterwards.
- Startup fails with `CoSecSchemaException` naming `<prefix>:meta:schema`: CoSec data exists (under the 6.x `legacy-key-prefix` or the current prefix) without the 7.0 schema marker, so it is 6.x data or data rolled back to 6.x that 7.0 would read as missing (typically a key prefix ending in `:` or a changed prefix). Stop every writer and run `CoSecCoCache5Migration.migrate()`, which writes the marker, from a job with `cosec.authorization.cache.migration.check-schema=false`; never set that on serving instances, and never write the marker by hand while 6.x data may exist.
- `CoSecCoCache5Migration.rollback()` fails with `KeysWithoutBackupException`: 7.0 created those keys (policies, app permissions, revocations) and 6.x would overwrite them with a cached miss on first read, silently dropping denies and un-revoking tokens. Review the keys, call `rollback(allowKeysWithoutBackup = true)`, and write those policies and revoke those tokens again through 6.x before it serves traffic.
- `Policy id [history:...] must not start with [history:]` on `setPolicy` (7.0+): ids starting with `history:` would share a key with the policy history (`{prefix}:policy:history:<id>`). Rename the policy.
- Publishing a policy before the migration (`setPolicy`, or local policies with `force-refresh=true`) fails with `CacheWriteException` caused by `ForeignFormatException` on the global policy index key (`{prefix}:global:policy`): CoCache 6 does not overwrite the 6.x native SET. The index is untouched and still migrates; run the migration, then repeat the publish. Do not delete the key or set `foreign-format=reclaim`: it is the only copy of the global policy ids. Puts of app permissions are not checked and replace the 6.x value without a backup (role grants go to new keys and show up as `conflicts` in `RolePermissionKeyMigration`), so stop every writer until the migration has finished.
- A global policy id in the index without content fails every request with `MissingPolicyException` (500) since 7.0, instead of being skipped; rewrite the policy, or save it with a non-`global` type to drop it from the index. Indexed ids whose content is not `global` are ignored.
- Values written straight to Redis (`SET`, `SADD`) are not read in 7.0: every value carries a binary header. Write app permissions and role grants through the `AppPermissionCache` and `RolePermissionCache` beans, and policies through `PolicyRepository.setPolicy`.

### One principal always gets 500

- `MissingPolicyException` (`source = PRINCIPAL`, "The principal lists policies ...") names policy ids the principal lists but `PolicyRepository.getPolicies` did not return. With `source = GLOBAL_POLICY_INDEX` ("The global policy index lists policies ... that have no content") every request fails instead: the Redis global index lists a policy whose content is gone; write the policy again. Since 7.0 authorization fails closed on them instead of skipping them (a missing deny must not let a global allow win); explanations show a `MISSING_POLICY` warning.
- Check whether the policy was deleted, never initialized (`init-repository` off, or skipped at load), or lost from Redis. Fix by writing the policy or removing the id from the principal; do not suggest catching the exception.

### Matcher mismatch

- Path variables use `{id}` and conditions read `request.path.var.id`; `:id` and `request.pathVariable.id` are invalid.
- `PathVariableScopeException` at startup or on a policy read/write (7.0+): a policy or app permission condition, or a statement, permission or rate limit rule whose action is not `path`, reads `request.path.var.*` (or an expression mentions `PATH_VARIABLES`). Variables belong only to the statement whose own `path` action captured them; move the reference there. Local policy files with this error stop startup instead of being skipped; Redis-stored ones fail reads with 500.
- Path SpEL templates use `#{principal.id}`. SpEL condition expressions use root properties such as `context.principal.id`; they are different evaluation roots.
- Valid part prefixes are singular `request.header.`, `request.attributes.`, `request.path.var.`, and `context.principal.attributes.`. Unsupported parts fail loading with `Condition [<type>] part [<part>] is not a supported part` (7.0+); a policy stored by an earlier version fails its reads with 500.
- Regex timeouts fail closed as a deny: 401 for anonymous callers, 403 for authenticated ones (7.0+; always 403 before). Simplify or bound the pattern instead of increasing exposure to ReDoS.

### Rate limiting differs across instances

- Without the cache capability and a `StringRedisTemplate` bean, the `RateLimiter` is `LocalRateLimiter`: per JVM, so N instances allow N times the limit. Check which `RateLimiter` bean is active.
- Counter-store outages skip a rule by default. Set the rule's `strictFailure: true` only when availability loss should return 429.
- Denied requests (401/403) never spend permits. A rule that never applies usually has a non-matching `action`/`condition`, or its file is outside `cosec.limiter.locations`.
- A rule without `key` shares one bucket among all matching requests; requests whose `key` part is empty also share one bucket. Quotas belong to the rule `id`.
- After upgrading to 6.1, policies using `rateLimiter`, `groupedRateLimiter`, `redisRateLimiter`, or `redisGroupedRateLimiter` fail to parse: migrate them to rate limit rules.

## Reproduce narrowly

Prefer the smallest existing test layer that crosses the failing boundary:

- matcher behavior: create the real factory with `mapOf(...).asConfiguration()` and test `match`;
- policy parsing: load the exact resource with `LocalPolicyLoader`, assert its ID, then run `DefaultPolicyEvaluator.evaluate`;
- decision order: test `SimpleAuthorization` with the smallest repositories needed;
- transport mapping: use the WebFlux, WebMVC, or Gateway filter tests for 401/403/429 behavior;
- Redis behavior: run the cache integration test with Redis.

In this repository, run a focused class first, for example:

```bash
./gradlew :cosec-core:test --tests "me.ahoo.cosec.policy.DefaultPolicyEvaluatorTest"
```

Use `$cosec-policy-author` to rewrite a confirmed-bad policy and `$cosec-integration` to change confirmed-bad Spring configuration. For diagnosis-only requests, report the root cause and evidence without modifying files.

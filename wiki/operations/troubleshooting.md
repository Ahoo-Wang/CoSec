---
title: "Troubleshooting"
description: "Find out why a request was denied, allowed or failed, starting from the symptom."
---

# Troubleshooting

For developers and operators facing an unexpected decision or a startup failure. Start from the symptom you see;
each section says how to confirm the cause and fix it, and links to the page that holds the facts. Quoted messages
are exactly what CoSec logs or returns; `<...>` and `[...]` stand for the values it fills in. The explain API itself
is documented in [Checks in code](../integration/checks.md#explaining-a-decision).

## Find out what decided

Every decision names the rule that made it. Before anything else:

1. Take the `CoSec-Request-Id` header of the response.
2. Find its audit event: denials and errors are logged by `me.ahoo.cosec.audit` at `WARN` and `ERROR` by default.
   Its `authorization.source` says which layer decided (`BLACKLIST`, `ROOT`, `GLOBAL_POLICY`, `PRINCIPAL_POLICY`,
   `ROLE_PERMISSION`, or `NONE` when nothing matched) and which policy statement or role permission
   ([Audit events](./observability.md#audit-events)).
3. For the full picture, every rule checked and why it matched or missed, explain the decision with the caller's
   real token ([Explaining a decision](../integration/checks.md#explaining-a-decision)). With
   `opentelemetry-support` the deciding rule is also on the `cosec.authorize` span ([Tracing](./observability.md#tracing)).

## By status code

| Status | Reason | Go to |
|---|---|---|
| `400` | — | The path has `.` or `..` segments (also before a `;` path parameter, as in `..;`) or a malformed percent-escape ([The request path](../integration/web.md#the-request-path)). Fix the client. |
| `401` | `Token Expired`, `Token Invalid` | [Unexpected 401](#unexpected-401) |
| `401` | `Implicit Deny` | No credential was found, and no rule allows anonymous access, or a `regular` condition timed out: [Unexpected 401](#unexpected-401) |
| `401`, `403` | `Explicit Deny` | A deny matched: [Unexpected 403](#unexpected-403) |
| `403` | `Implicit Deny` | Nothing allowed the request, or a `regular` condition timed out: [Unexpected 403](#unexpected-403) |
| `429` | `Too Many Requests` | [Rate limits behave unexpectedly](#rate-limits-behave-unexpectedly) |
| `500` | `Implicit Deny` | An error during authorization: [Every request fails with 500](#every-request-fails-with-500), [One principal always gets 500](#one-principal-always-gets-500), [Every request of one role fails with 500](#every-request-of-one-role-fails-with-500) |

How decisions map to statuses and response bodies is defined once, for every adapter, in
[Responses and status codes](../integration/web.md#responses-and-status-codes).

## Unexpected 401

The caller was treated as anonymous: the credential was missing, or rejected.

1. **Was the token sent where CoSec reads it?** The `Authorization` header or an `authorization` cookie; the query
   parameter only with `cosec.authorization.allow-query-token=true` ([Tokens](../integration/authentication.md#tokens)).
2. **`Token Invalid`**: the issuer and this service must share `cosec.jwt.secret` and `cosec.jwt.algorithm`. A
   revoked token is also reported as invalid.
3. **`Token Expired`**: refresh it ([Refreshing tokens](../integration/authentication.md#refreshing-tokens)), or check the clocks and `cosec.jwt.token-validity.access`.
   A refresh that fails with `RefreshTokenMismatchException` was sent a refresh token issued with another access
   token: send the pair that was issued together.
4. **`Token Invalid` with an API key**: the key's SHA-256 is not configured, or the key is disabled or expired
   ([API keys](../integration/authentication.md#api-keys)). A request carrying `X-API-Key` ignores its JWT.
5. **A logged-out token still works on another instance.** A revocation reaches every instance through an
   invalidation message; if that message is lost, a cached "not revoked" answer lives at most 30 seconds
   ([Storage](./storage.md#the-caches)).
6. **No credential at all, `Implicit Deny`**: no rule allows anonymous access to this path. Send a credential, or
   allow the path for anonymous callers on purpose. If an allow should have matched, check its `regular`
   conditions: one that timed out denies like any other deny (see [Unexpected 403](#unexpected-403)).

## Unexpected 403

The caller is known, and nothing allowed the request, or something denied it. An anonymous caller gets `401` for the
same causes.

1. **Read the deciding rule** in the audit event or the explanation. `Explicit Deny` names the deny that won. A deny
   wins across all layers, so a global allow cannot override it ([Deny first](../guide/security-model.md#deny-first)).
2. **For `Implicit Deny`, check the rule you expected to match:**
   - its **action**: method, trailing slash, `*` (one segment) against `**` (any number);
   - its **condition** and the principal it saw: roles, attributes and tenant come from the token, so changes apply
     only after a new login or refresh;
   - for a principal policy, that the policy id is in the token's `policies`;
   - for a role permission, the `CoSec-App-Id` and `CoSec-Space-Id` headers and the role's grants in that app and
     space. Grants apply only in their own app, and with `CoSec-Space-Id: x` only roles written `role@x` count;
     without the header, only roles without a suffix. The explanation warns `NO_ROLE_IN_SPACE` or
     `NO_APP_PERMISSION` ([Role grant scope](../policies/role-permissions.md#role-grant-scope));
   - `inTenant` takes a tenant *kind* (`default`, `user`, `platform`), not a tenant id.
3. **A `regular` condition that timed out** denies (`401` for an anonymous caller) with the reason code
   `REGEX_TIMEOUT` in the audit event. Simplify the pattern.
4. **After upgrading to 7.0**, role grants that were not migrated to an app are missing there
   ([Migrating role grants](./upgrading.md#migrating-role-grants)).

## Request allowed unexpectedly

1. **Read the deciding rule** in the audit event or the explanation; `overriddenAllows` and `layers` show what else
   matched.
2. **Root.** While `cosec.authorization.root-enabled` is `true`, the platform tenant's principal whose id is `cosec`
   (or the `cosec.root` system property) bypasses every check except the blacklist
   ([Root principal](../guide/security-model.md#root-principal)).
3. **A broad allow.** Look for a global `allow` with `"*"`, `/**` or no condition. Add a deny, or narrow the allow.
4. **A deny that does not match.** Its action and its condition must both match; a negated condition is a common
   mistake. Explain the request and read the deny's `outcome`: `ACTION_MISS` or `CONDITION_MISS`.
5. **A role condition written with `in`, `spel` or `ognl`.** These see raw role strings, so a bare role name stops
   matching once tokens carry `role@space`; use `inRole` ([Matching roles](../policies/language.md#matching-roles)).

## Policy has no effect

1. **Local files are loaded but not used.** Authorization reads only the `PolicyRepository`. Set
   `cosec.authorization.local-policy.init-repository=true`, or write the policy to the repository yourself
   ([Local policy files](./storage.md#local-policy-files)).
2. **The policy already exists.** At startup an existing id is kept unless `force-refresh=true`; the log says
   `Init Policy - [<id>] already exists,Ignore setting policy.`
3. **The file is not in a configured location.** An invalid file, a duplicate policy id and a configured location
   that matches no file all stop startup ([Startup fails loading a policy file](#startup-fails-loading-a-policy-file)), so a file
   that has no effect after a clean start is usually outside `cosec.authorization.local-policy.locations`: the default
   `classpath:cosec-policy/*-policy.json` matches only names ending in `-policy.json`. Search the startup log for
   `Load Policy [` followed by the file name.
4. **Wrong policy type.** Only `global` policies apply to everyone; `system` and `custom` policies apply only to
   principals that list them.
5. **Written straight to Redis.** Values written with `SET` or `SADD` are not read. Write app permissions and role
   grants through the `AppPermissionCache` and `RolePermissionCache` beans ([Write through CoSec](./storage.md#write-through-cosec)).
6. **A changed role or attribute.** The principal comes from the token; the change applies after a new login or
   refresh.
7. **After upgrading to 7.0**, data that was not migrated fails its reads with `ForeignFormatException` and
   requests get `500` ([Migrating the data](./upgrading.md#migrating-the-data)).

## Every request fails with 500

Find the exception in the application log: CoSec logs
`Unexpected error during authorization of request [<path>] [<method>].` or
`Unexpected error while parsing security context of request [<path>] [<method>].` with the stack trace. Then:

| Exception or message | Cause | Fix |
|---|---|---|
| Redis connection errors | Redis is unreachable and the value is not in the local copy. Policy and permission reads fail closed on purpose ([Storage](./storage.md#when-redis-is-unavailable)). | Restore Redis. |
| `CacheReadException` caused by `ForeignFormatException` | The value was written by CoSec 6.x and not migrated, or written with plain Redis commands. | [Migrate the data](./upgrading.md#migrating-the-data), or write it again through CoSec. |
| `MissingPolicyException: The global policy index lists policies [...] that have no content.` (`source` is `GLOBAL_POLICY_INDEX`) | A global policy's content was lost, or not migrated from 6.x (a 6.x cached miss migrates as a miss). It may be a global deny, so CoSec fails rather than skips it ([Global policy index](./storage.md#the-global-policy-index)). | Write the policy again with `PolicyRepository.setPolicy`; to drop it instead, save it with a type other than `global`, which removes it from the index. |
| `Condition [<type>] part [<part>] is not a supported part` while reading a policy | A policy stored by a version before 7.0 names a part that does not exist ([Parts](../policies/language.md#parts)). Since 7.0 such a condition fails when the policy loads, so a local file stops startup and `setPolicy` rejects it. | Fix and save the policy again; [validate policies](../policies/testing.md#validating-policies) before deploying. |
| `PathVariableScopeException` naming a policy and statement | A stored policy reads `request.path.var.*` outside its scope, which 7.0 rejects ([Path variables](../policies/language.md#path-variables)). | Fix and save the policy again. |
| an exception from your code | A custom matcher, repository or `ApiKeyStore` threw. | The stack trace names it. |

With the Redis API key store, a Redis failure during the key lookup also answers `500`.

## One principal always gets 500

The log shows `MissingPolicyException`:
`The principal lists policies [...], which the policy repository does not have.`, and its `source` is `PRINCIPAL`.
(When it reads `The global policy index lists policies [...] that have no content.` instead, the missing policy is a
global one and every request fails: see [Every request fails with 500](#every-request-fails-with-500).) The principal
lists a policy that
was deleted, never written, or lost from Redis. Authorization fails closed rather than skipping it, because a missing
deny would otherwise let a broader allow through. Write the policy again (for local policies, see
[Policy has no effect](#policy-has-no-effect)), or remove the id from the principal's token or record. An
explanation reports it as a `MISSING_POLICY` warning.

## Every request of one role fails with 500

The log shows `MissingPermissionException`:
`Role [<role>] is granted permissions [...], which app [<app>] does not define.` The role's grant in that app names a
permission id that the app's catalog does not have: it was removed from the catalog, or the id is mistyped. A request
of a principal holding the role fails whenever the role layer is evaluated and no deny matched first, rather than
skipping the id, because a missing deny would otherwise let a broader allow through. A request outside the app's
condition, or already denied, is not affected
([Granting permissions to roles](../policies/role-permissions.md#granting-permissions-to-roles)). Add the permission to
the app's catalog, or remove the id from the role's grant. An explanation reports it as a `MISSING_PERMISSION` warning.

## Startup failures

### Startup fails loading a policy file

| Message | Cause |
|---|---|
| `Failed to load policy [<file>]: <reason>` | A local policy file cannot be parsed or built: malformed JSON, an unknown action or condition type, an invalid option, or an unsupported condition part (`Condition [<type>] part [<part>] is not a supported part`). The cause below says which. |
| `Policy id [<id>] is declared more than once: [<files>].` | Two local policy files declare the same id. Give each its own id, or remove the copy. |
| `Policy location [<location>] matches no file.` | A location in `cosec.authorization.local-policy.locations` does not exist or its pattern matches nothing, usually a typo. Only the default may match nothing. |
| `Rate limit rule location [<location>] matches no file.`, `Rate limit rule id [<id>] is declared more than once: [...]` | The same for `cosec.limiter.locations`. |
| `Shadow policy location [...]`, `Shadow app permission location [...]`, and their `id [...] is declared more than once` | The same for `cosec.shadow.locations` and `cosec.shadow.app-permission-locations`. |
| `Rate limit rule [<id>] key [<key>] is not a supported part (e.g. request.remoteIp, context.principal.id).` | A rate limit `key` names an unsupported part. |

Any of these stops startup on purpose: skipping the file could silently drop a deny or a quota. Fix or remove the
file ([Loading policy files](../policies/language.md#loading-policy-files),
[Loading rules](../policies/rate-limiting.md#loading-rules)). The defaults `classpath:cosec-policy/*-policy.json`, `classpath:cosec-rate-limit/*-rate-limit.json`,
`classpath:cosec-shadow/*-policy.json` and `classpath:cosec-shadow/*-app-permission.json` may match nothing.

### Startup fails with PathVariableScopeException

A local policy file, an app permission or a rate limit rule reads `request.path.var.*` where no `path` action of its
own captures the variables. The message names the policy, statement or rule and ends with
`Use a statement whose action is a path pattern that captures the variable.` Move the reference into a statement (or
rule) whose `path` action declares the variable, or move a policy condition into its statements' conditions. An
action's own configuration, such as a `path` template, may not read them at all; the message then says
`a path template cannot use them. Read them in the statement's condition instead.`
([Path variables](../policies/language.md#path-variables)).

### Startup fails reading a stored policy

With `cosec.authorization.local-policy.init-repository=true` and `force-refresh=false` (the default), startup first
reads each local policy's id from the repository and writes the file only when the id is missing. When the stored
copy cannot be read, startup fails with `Init Policy - [<id>] failed.` and the read error, even if the local file is
valid:

- `CacheReadException` caused by `ForeignFormatException`: the copy is 6.x data that was not migrated. Run the
  [data migration](./upgrading.md#migrating-the-data).
- `PathVariableScopeException`: the stored copy (often migrated from 6.x) reads `request.path.var.*` outside its
  scope. Fix the stored policy, or start once with `force-refresh=true` so the local files overwrite the stored copies
  without reading them.

::: warning `force-refresh=true` overwrites
It replaces the stored copy of **every** local policy with its file, including changes administrators made through
the repository since, and it is no fix for unmigrated data: before the migration every publish fails
([below](#publishing-a-policy-fails-before-the-migration)). Use it only after the migration, once, and turn it off
again.
:::

### Startup fails with CoSecSchemaException

The message starts `Redis key [<p>:meta:schema] is missing but CoSec data exists (for example [<key>])`. The data
under the 6.x key prefix (`cosec.authorization.cache.migration.legacy-key-prefix`) or the current one was written by
6.x, or rolled back to it, and 7.0 would read it as missing, typically because the key prefix ends with `:` or changed
([Schema marker](./storage.md#schema-marker)).

- Stop every writer and run the [data migration](./upgrading.md#migrating-the-data), which writes the marker. Only the
  job that runs it sets `cosec.authorization.cache.migration.check-schema=false`.
- Data that 7.0 itself wrote, for example in a test environment that ran 7.0 before the marker existed, is fixed the
  same way: `migrate()` skips what is already migrated and writes the marker.
- Do not write the marker by hand while 6.x data may exist.
- `records CoSec data schema [<version>], but this CoSec reads schema [7]` means a newer CoSec wrote the data.

### Startup fails with a JWT secret error

`Property [cosec.jwt.secret] must not be blank when JWT is enabled.` Set `cosec.jwt.secret` to a long random value
from a secret store, or turn JWT off with `cosec.jwt.enabled=false` ([Configuration](./configuration.md#jwt)).

### Startup fails on a cocache property

CoCache 6 removed or renamed several 5.x properties, such as `cocache.redis.strict-failure`; the error names the
replacement ([Configuration](./configuration.md#cocache-properties-that-affect-cosec)).

## Publishing a policy fails with a reserved id

`Policy id [history:...] must not start with [history:], which holds the policy history.`: the policy history keeps
the revisions of policy `<id>` under `<prefix>:policy:history:<id>` ([Redis keys](./storage.md#redis-keys)), so ids
starting with `history:` are reserved. Rename the policy (for example `policy-history-...`) and update the principals
that list it.

## Publishing a policy fails before the migration

`setPolicy` (an administration API, or local policies with `force-refresh=true` at startup) fails with
`CacheWriteException` caused by `ForeignFormatException`, naming the global policy index key (`cosec:global:policy`)
and `format[0]`. The index is still the native SET CoSec 6.x wrote, and CoCache 6 does not overwrite it: the index is
left unchanged, and requests keep failing with `500` until the data is migrated. Run the
[data migration](./upgrading.md#migrating-the-data), then repeat the publish.

Do not delete the key or set `foreign-format=reclaim` on it: it is the only copy of the global policy ids. A global
policy's content was already written in the 7.0 format before the index update failed, so the migration skips it and
a rollback keeps it in the 7.0 format ([Writes before the migration](./upgrading.md#writes-before-the-migration)).

## Rate limits behave unexpectedly

1. **Which rule?** The audit event's decision is `TOO_MANY_REQUESTS`; the explanation lists the matching rules in
   `rateLimitRules`. The response's `RateLimit-*` and `Retry-After` headers give the limit and when to retry
   ([Responses and headers](../policies/rate-limiting.md#responses-and-headers)).
2. **Unrelated callers share a limit.** A rule without `key` has one bucket for all matching requests, and requests
   whose `key` part is empty (for example a missing header) share one bucket too.
3. **`429` during a Redis outage.** The rule sets `strictFailure: true`; the log says
   `Rate limiter unavailable - rule [<id>] fails closed.` Without it the rule is skipped
   (`Rate limiter unavailable - rule [<id>] is skipped.`).
4. **The limit is N times higher than configured.** Without `cache-support` and a `StringRedisTemplate`, the
   in-memory limiter counts per instance. Add Redis for a cluster-wide limit
   ([Counting and backends](../policies/rate-limiting.md#counting-and-backends)).
5. **A rule never applies.** Check its `action` and `condition` like a policy statement's, and that the file matches
   [`cosec.limiter.locations`](./configuration.md#rate-limiting). Denied requests are never limited, so a request
   that gets `401` or `403` spends no permit.
6. **Policies fail after upgrading to 6.1.** The `rateLimiter`, `groupedRateLimiter`, `redisRateLimiter` and
   `redisGroupedRateLimiter` conditions were removed ([6.0 to 6.1](./upgrading.md#_6-0-to-6-1)).

## Audit events are missing

1. `cosec.audit.enabled` must be `true` (the default).
2. With the logging sink, allows are logged at `DEBUG`: set `me.ahoo.cosec.audit` to `DEBUG` to see them.
3. With Kafka, look for `Dropping audit events because the Kafka audit queue is full or shutting down.` or
   `Failed to publish audit event to Kafka topic [<topic>].` ([Sinks](./observability.md#sinks)).
4. A custom sink that throws is logged as `Failed to publish audit event.`

**Next:** move an existing deployment to a newer version, in [Upgrading](./upgrading.md).

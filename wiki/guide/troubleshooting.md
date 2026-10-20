# Troubleshooting

Start from what the response tells you, find out which rule decided, then check the inputs that rule saw.

## Find out what decided

Every decision names the rule that made it. Turn on debug logging:

```yaml
logging:
  level:
    me.ahoo.cosec.authorization.SimpleAuthorization: DEBUG   # matched policy statement or role permission
    me.ahoo.cosec.audit: DEBUG                               # audit events (denials are logged at WARN)
```

Each audit event records the principal, the request, the result and its **source**: `BLACKLIST`, `ROOT`,
`GLOBAL_POLICY`, `PRINCIPAL_POLICY`, `ROLE_PERMISSION`, or `NONE` when nothing matched (see
[Auditing](./operations.md#auditing)). Correlate a response with its event through the `CoSec-Request-Id`
header. With `opentelemetry-support` the same information is on the `cosec.authorize` span.

To see every rule that was checked, not just the one that decided, [explain the decision](#explain-a-decision).

## Explain a decision

To see why a principal gets a decision for a request, without sending the real request, ask the explainer.
It runs the real authorization against a snapshot of the repositories, then walks every policy statement and
role permission and says which matched, which missed on the action, and which missed on the condition. Nothing
is spent or changed: rate limits are only listed, and custom matchers can skip side effects by checking
`securityContext.isPolicyEvaluation`.

Enable it with `cosec.explain.enabled=true`. With Spring Boot Actuator, expose the endpoint and allow only
administrators to call it, because an explanation reveals your policy structure:

```yaml
cosec:
  explain:
    enabled: true
management:
  endpoints:
    web:
      exposure:
        include: cosecExplain
```

The endpoint goes through CoSec like any other request, so it also needs a policy that allows
`POST /actuator/cosecExplain` for your administrators.

```bash
curl -X POST localhost:8080/actuator/cosecExplain -H 'Content-Type: application/json' -d '{
  "principal": { "id": "user-1", "tenantId": "t1", "roles": ["clerk"], "policies": ["orders"] },
  "request":   { "path": "/orders/42", "method": "DELETE", "appId": "shop" },
  "exhaustive": false,
  "detail": "summary"
}'
```

| Field | Meaning |
|---|---|
| `status`, `authorized`, `reason` | The real decision, mapped to a status exactly as for a real request. |
| `decidedBy` | The rule that decided: layer, policy id and statement index, or role and permission id. `null` when nothing matched. |
| `layers` | `BLACKLIST`, `ROOT`, `GLOBAL_POLICY`, `PRINCIPAL_POLICY`, `ROLE_PERMISSION`, each `EVALUATED`, `NOT_EVALUATED` (an earlier explicit deny ended evaluation), `EMPTY`, or `EVALUATED_AFTER_DECISION` (with `exhaustive: true`). Every statement and permission has an outcome: `MATCHED`, `ACTION_MISS`, `CONDITION_MISS`, `NOT_EVALUATED` (its policy's or app's condition did not match) or `ERROR`. |
| `overriddenAllows` | Allows that a later layer's explicit deny overrode. |
| `rateLimitRules` | The [rate limit rules](./rate-limiting.md) the request would spend a permit from, if it is allowed. |
| `warnings` | `MISSING_POLICY` (the principal lists a policy id the repository lacks, so authorization fails with `500`), `NO_APP_PERMISSION` (roles in the space, but no app permission for the app), `NO_ROLE_IN_SPACE` (roles, but none written for the request's space; see [Role grant scope](./policies.md#role-grant-scope)), `MATCHER_ERROR`, `TOKEN_INVALID` (the request's token or API key was rejected), and `TRACE_INCONSISTENT` (the trace disagrees with the real decision; trust the decision and report it). |

To explain what a real caller gets, leave out `principal` and put their credential in the request's headers, for
example `"headers": { "Authorization": "Bearer eyJ..." }` or `{ "X-API-Key": "..." }`. The credential is parsed by
the application's own security context parser, including revocation checks and API keys. A rejected credential is
explained as the anonymous request it becomes, with its real `401` reason and a `TOKEN_INVALID` warning. The
credential is only read from the request body, never logged.

`detail: "full"` adds each matcher's configuration. Without Actuator, inject the `AuthorizationExplainer`
bean and call `explain(request, principal)` yourself, for example in a test that pins down who may do what.

## By status code

The status codes and their reasons are defined in [Web Integration](./web-integration.md#responses).

| Status | Reason | Look at |
|---|---|---|
| `400` | — | The path contains `.` / `..` segments or a malformed percent-escape. Fix the client. |
| `401` | `Token Expired` / `Token Invalid` | The token, not the policy: see [Unexpected 401](#unexpected-401). |
| `401` | `Implicit Deny` | No token was found, and no rule allows anonymous access to this path. |
| `401` / `403` | `Explicit Deny` | A `deny` statement or permission matched. The debug log names it. |
| `403` | `Implicit Deny` | The caller is authenticated but no rule allowed the request, or a `regular` condition timed out. |
| `429` | `Too Many Requests` | A [rate limit rule](./rate-limiting.md): see [Rate limits behave unexpectedly](#rate-limits-behave-unexpectedly). |
| `500` | `Implicit Deny` | An error during authorization. The application log has the stack trace. |

## Unexpected 401

The caller was treated as anonymous.

- **Was a token sent where CoSec reads it?** The `Authorization` header or an `authorization` cookie; the
  query parameter only with `cosec.authorization.allow-query-token=true`. See
  [Authentication](./authentication.md#tokens).
- **`Token Invalid`**: the issuer and this service must share `cosec.jwt.secret` and `cosec.jwt.algorithm`.
  A revoked token is also reported as invalid.
- **`Token Expired`**: refresh it ([Refreshing](./authentication.md#refreshing)), or check the clocks and
  `cosec.jwt.token-validity.access`.
- **`Token Invalid` with an API key**: the key's SHA-256 is not configured, or the key is disabled or expired
  ([API keys](./authentication.md#api-keys)). A request carrying `X-API-Key` ignores its JWT.

## Unexpected 403

The caller is known, but nothing allowed the request, or something denied it.

- Read the matched rule in the debug log. `Explicit Deny` names the deny that won; deny wins across all
  layers, so a global `allow` cannot override it.
- For `Implicit Deny`, check the rule you expected to match:
  - its **action**: method, trailing slash, `*` (one segment) versus `**` (any number);
  - its **condition** and the principal it saw: roles, attributes and tenant come from the token, so
    changes apply only after a new login or refresh;
  - for principal policies, that the policy id is in the token's `policies`;
  - for role permissions, the `CoSec-App-Id` and `CoSec-Space-Id` headers and the role's grants in that app
    and space. Grants apply only in their own app, and with `CoSec-Space-Id: x` only roles written `role@x` in
    the token count (without the header, only unsuffixed roles). After upgrading to 7.0, grants that were not
    migrated to an app are missing ([Migrating role grants](./upgrading.md#migrating-role-grants)).
- `inTenant` takes a tenant *kind* (`default`, `user`, `platform`), not a tenant id.

## Request allowed unexpectedly

- **Root principal**: while `cosec.authorization.root-enabled` is `true`, the platform tenant's principal with id
  `cosec` (or the `cosec.root` system property) bypasses every check except the blacklist.
- **A broad allow**: look for a global `allow` with `"*"`, `/**` or no condition. Add a `deny`, or narrow the
  allow.
- **A deny that does not match**: its action and its condition must both match. A negated condition is a
  common mistake.

## Policy has no effect

- **Local files are loaded but not used.** Authorization reads the `PolicyRepository`. Set
  `cosec.authorization.local-policy.init-repository=true`, or write the policy to the repository yourself.
- **The policy already exists.** On startup, an existing id is kept unless `force-refresh=true`.
- **The file was skipped.** Invalid files are logged at `ERROR` and skipped
  ([Loading policies](./policies.md#loading-and-generating-policies)). Search the startup log for the file name.
- **Another file has the same id.** Only the first one with a given id is loaded.
- **Wrong policy type.** Only `global` policies apply to everyone; `system` and `custom` policies apply only
  to principals that list them.
- **The data was not migrated when upgrading to 7.0.** 7.0 cannot read what 6.x stored in Redis: reads fail with
  `ForeignFormatException` and requests get `500`. Migrate it ([Migrating the data](./upgrading.md#migrating-the-data)).
- **Permissions were written straight to Redis.** Since 7.0, values written with `SET` or `SADD` are not read; write
  app permissions and role grants through the `AppPermissionCache` and `RolePermissionCache` beans
  ([App permissions and roles](./policies.md#app-permissions-and-roles)).

## Every request fails with 500

- **Redis is unreachable** and the policy or permission is not in the local cache. This is deliberate: policy
  reads fail closed ([Redis and caching](./operations.md#redis-and-caching)).
- **A condition names an unsupported part.** The log shows `Unsupported part`; see the list of
  [parts](./policies.md#value-conditions). [Validating policies](./policies.md#validating-policies) catches
  this before deployment.
- **A custom matcher or repository throws.** The stack trace names it.
- **A stored policy reads path variables outside their scope.** The log shows `PathVariableScopeException`
  naming the policy and statement. Since 7.0, a policy condition, or the condition of a statement without a
  `path` action, may not read `request.path.var.*`. Fix and re-save the policy
  ([Path variables](./policies.md#actions)).

## Startup fails with PathVariableScopeException

A local policy file, an app permission or a rate limit rule reads `request.path.var.*` where no `path` action of
its own captures the variables. The message names the file, the policy, statement or rule, and the action type.
Move the reference into a statement (or rule) whose `path` action declares the variable, or move a policy
condition into its statements' conditions ([Path variables](./policies.md#actions)). Before 7.0 such a reference
read the variables of whichever statement had matched last. An action's own configuration, such as a `path`
template that mentions `PATH_VARIABLES`, may not read them at all: read them in the statement's condition instead.

## Startup fails loading a policy file

`Failed to load policy [<file>]` at startup means a local policy file (`cosec.authorization.local-policy.locations`)
could not be parsed or built: malformed JSON, an unknown action or condition type, or an invalid option. The cause
below the message says which. Since 7.0 any such file stops startup; before, it was logged and skipped, which could
silently drop a deny. Fix or remove the file. A location that does not exist is only logged as a warning.

## One principal always gets 500

The log shows `MissingPolicyException` with the policy ids. The principal lists a policy that the repository
does not have: it was deleted, never written, or lost from Redis. Authorization fails closed rather than skipping
it, because a missing deny would otherwise let a broader allow through. Write the policy again (for local policies,
see [Policy has no effect](#policy-has-no-effect)), or remove the id from the principal's token or record.
An [explanation](#explain-a-decision) reports it as a `MISSING_POLICY` warning.

## Rate limits behave unexpectedly

- **The limit is N times higher than configured.** Without `cache-support` and a `StringRedisTemplate`,
  `LocalRateLimiter` counts per instance. Add Redis for a cluster-wide limit
  ([Counting](./rate-limiting.md#counting)).
- **A rule never applies.** Check its `action` and `condition` like a policy statement's, and that the file
  matches [`cosec.limiter.locations`](./configuration.md#rate-limiting). Denied requests are never limited,
  so a request that gets `401` / `403` spends no permit.
- **Unrelated callers share a limit.** A rule without `key` has one bucket for all matching requests, and
  requests whose `key` part is empty (e.g. a missing header) share one bucket too.
- **Requests pass during a Redis outage.** Rules skip themselves on backend failures unless they set
  `strictFailure: true`.
- **Policies fail after upgrading to 6.1.** The `rateLimiter`, `groupedRateLimiter`, `redisRateLimiter` and
  `redisGroupedRateLimiter` conditions were removed: see
  [Migrating from rate-limit conditions](./rate-limiting.md#migrating-from-rate-limit-conditions).

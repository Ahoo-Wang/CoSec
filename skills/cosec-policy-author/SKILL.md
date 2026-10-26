---
name: cosec-policy-author
description: Write, review, validate, or explain CoSec policy and rate limit rule JSON, including allow/deny precedence, action and condition matchers, tenant and role rules, and per-IP or per-user quotas. Do not use for Spring Boot setup.
---

# CoSec Policy Authoring

Produce the smallest policy that expresses the requested access rule. Establish the endpoint paths and methods, anonymous/authenticated behavior, roles or principal attributes, tenant scope, and explicit deny cases before writing JSON.

## Minimal complete policy

```json
{
  "id": "orders-api",
  "name": "Orders API",
  "category": "orders",
  "description": "Order endpoint access",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "ReadOwnOrder",
      "action": {
        "path": {
          "method": "GET",
          "pattern": "/users/{userId}/orders/*"
        }
      },
      "condition": {
        "eq": {
          "part": "request.path.var.userId",
          "value": "#{principal.id}"
        }
      }
    }
  ]
}
```

Runtime deserialization and the bundled JSON Schema both require `id`, `name`, `type`, and `tenantId`; include `category`, `description`, and `statements` anyway for readability. A statement requires `action`; `effect` defaults to `allow` and `condition` defaults to match-all. Local files normally live under `src/main/resources/cosec-policy/` and match `*-policy.json`.

## Evaluation semantics

Authorization evaluates in tiers: blacklist, root bypass (off by default; platform tenant only), global policies, principal-attached policies, then role permissions. The policy and permission tiers are combined deny-first (CoSec 6+): an explicit deny in any tier ends evaluation, and an allow is returned only after every tier has had the chance to deny.

Within a policy tier, CoSec first evaluates every matched policy-level condition, pools their statements, checks all `deny` statements, then all `allow` statements. Therefore:

- a matching deny overrides every allow in the same tier, regardless of file or array order;
- a false policy-level condition skips that policy; it does not deny the request;
- a deny in any tier overrides an allow from any other tier, so a principal or role deny overrides a global allow;
- if no statement in any tier matches, the result is implicit deny.

Order deny statements before allow statements for readability, not behavior.

## Action forms

```json
"action": "/api/users/{id}"
```

```json
"action": ["/auth/login", "/auth/refresh"]
```

```json
"action": {
  "path": {
    "method": ["GET", "HEAD"],
    "pattern": ["/api/users/*", "/api/teams/*"]
  }
}
```

`"*"` or `{ "all": { "method": "GET" } }` matches all paths. `{ "composite": [...] }` ORs heterogeneous action matchers. Path patterns support variables such as `{id}` and SpEL templates such as `#{principal.id}`. A template value fills one literal segment only: a value that is empty, `.`, `..`, or contains `*`, `?`, `{`, `}` or `/` is rejected (no match in an allow, match-everything in a deny or rate limit rule). When ids or attributes may contain these characters, prefer a static pattern plus `eq` on the path variable (`/users/{userId}/**` with `request.path.var.userId` `eq` `#{principal.id}`).

## Condition reference

| Type | Required configuration | Purpose |
|---|---|---|
| `all` | none | Always match |
| `authenticated` | none | Require a non-anonymous principal |
| `inRole` | `value` | Match a principal role. Bare `admin` matches `admin` (any space) or `admin@<request space>`; `admin@team-a` matches that exact role string in any request space. Neither limits an allow to the request's space (deliberately, so a deny cannot be escaped by changing `CoSec-Space-Id`); add `{ "eq": { "part": "request.spaceId", "value": "team-a" } }` to scope an allow. Role grants in app permissions are already space-scoped (`RoleScope`). `in`/SpEL/OGNL over `context.principal.roles` see raw `role@space` strings |
| `inTenant` | `value`: `default`, `user`, or `platform` | Match tenant type, not tenant ID |
| `eq`, `contains`, `startsWith`, `endsWith` | `part`, `value`; optional `ignoreCase` | Compare an extracted string; only `eq.value` accepts a `#{...}` SpEL template |
| `in` | `part`, array `value` | Match one of several values |
| `regular` | `part`, `pattern`; optional `timeout` (ms, default 1000) | Case-insensitive whole-value regex match with a time budget |
| `path` | `part`, `pattern`, optional `options` | Spring path-pattern match on an extracted value |
| `bool` | optional sibling arrays `and`, `or` | Combine conditions |
| `time` | at least one of `notBefore`, `notAfter` (ISO date-times, exclusive end), `daysOfWeek`, `from`/`to` (`HH:mm`, may cross midnight); optional `zone` (default UTC) | Validity periods (self-expiring grants) and recurring windows, read from the server clock |
| `spel` | `expression` | Evaluate against root properties `request` and `context` |
| `ognl` | `expression` | Sandboxed expression using `#request` and `#context` |
`negate` is handled by matchers based on `AbstractConditionMatcher`, which covers every built-in condition.

### Rate limits

Rate limits are not policy conditions; since 6.1.0 the `rateLimiter`, `groupedRateLimiter`, `redisRateLimiter`, and `redisGroupedRateLimiter` conditions no longer exist, and a policy using one fails to parse. Write a rate limit rule instead, in a file matching `cosec.limiter.locations` (default `classpath:cosec-rate-limit/*-rate-limit.json`, one rule or an array, schema `schema/rate-limit-rule.schema.json`; a configured location that matches no file or a duplicate rule id fails startup, only the default may match nothing):

```json
{
  "id": "login-by-ip",
  "action": { "path": { "pattern": "/auth/login", "method": "POST" } },
  "condition": { "authenticated": { "negate": true } },
  "key": "request.remoteIp",
  "permitsPerSecond": 0.1,
  "windowSeconds": 60,
  "strictFailure": true
}
```

| Field | Notes |
|---|---|
| `id` | Required, `[A-Za-z0-9_.-]+`, unique. The quota belongs to the id. |
| `action`, `condition` | Same syntax as a statement; `condition` defaults to always. |
| `key` | Optional part (e.g. `request.remoteIp`, `context.principal.id`) giving one bucket per value; omit for one shared bucket. Empty values share one bucket. |
| `permitsPerSecond`, `windowSeconds` | A window of `windowSeconds` (default 1) allows `permitsPerSecond × windowSeconds` requests, at least one. |
| `strictFailure` | `false` (default) skips the rule when the counter store fails; `true` returns 429. |

Only requests that authorization allows spend permits. Every matching rule spends one, in order, and the first exhausted rule returns 429 with `Retry-After`. Counters are cluster-wide in Redis when the cache capability and `StringRedisTemplate` are present, otherwise per JVM. An unreadable rule file fails application startup. When migrating an old limiter condition, move the statement's action into `action`, its other conditions into `condition`, and `part` into `key`.

### Boolean example

```json
{
  "bool": {
    "and": [
      { "authenticated": {} }
    ],
    "or": [
      { "inRole": { "value": "admin" } },
      { "in": { "part": "context.principal.id", "value": ["support-1", "support-2"] } }
    ]
  }
}
```

All `and` entries must match. If `or` is present, at least one entry must match. `and` and `or` are sibling fields.

## Valid part paths

- `request.path`, `request.method`, `request.remoteIp`
- `request.origin`, `request.origin.host`, `request.referer`, `request.referer.host`
- `request.appId`, `request.spaceId`, `request.deviceId`
- `request.header.{name}`, `request.attributes.{key}`, `request.path.var.{name}`
- `context.tenantId`, `context.principal.id`, `context.principal.attributes.{key}`
- `resource.{key}`: attributes of the target object, passed by `AuthorizationChecker` checks in code (`CheckRequest(resource = ...)`) and empty in the request filter. Write object-level rules as denies guarded by a non-empty check (`eq` with `value: ""` and `negate: true`), e.g. deny unless `resource.ownerId` equals `#{principal.id}`; an allow that needs a resource part would block the route in the filter.

Use singular `request.header`. Path variables are scoped to one statement: `request.path.var.id` reads the `{id}` captured by the same statement's (or rate limit rule's) `path` action, and the variables are cleared before every statement. Reading them in a policy or app permission `condition`, or in a statement, permission or rate limit rule whose action is not `path` (`*`, `all`, `["*", ...]`, a `composite` with a non-path member), fails loading with `PathVariableScopeException`; this also covers OGNL/SpEL expressions and `#{...}` templates in conditions that mention `PATH_VARIABLES`. Any action configuration (e.g. a `path` template) that mentions `PATH_VARIABLES` or `request.path.var.` is rejected outright, because templates are evaluated before the action captures anything. A part absent from the request reads as an empty string; an unsupported part name in a condition, like an unsupported rate limit `key`, fails when the policy or rule loads (7.0+; a copy stored by an earlier version fails its reads with HTTP 500). Never base a deny on a header the client can omit or forge.

## Security review

Check the final policy for:

- broad global allows that grant more than intended to everyone (a principal or role deny still overrides them, but nothing else will);
- deny conditions whose negation is reversed;
- missing HTTP method constraints on state-changing endpoints;
- trusted-proxy assumptions behind `request.remoteIp`;
- tenant type (`inTenant`) being confused with tenant ID (`context.tenantId`);
- `inRole` allows meant for one space without a `request.spaceId` condition (they match in every request space);
- unsafe or needlessly complex expressions when a built-in matcher suffices;
- rate limit rules that count per JVM (no Redis) where a cluster-wide quota is required.

## Rolling out changes

For changes to production policies, suggest shadow mode before enforcing: with `cosec.shadow.enabled=true`, candidate versions placed in `classpath:cosec-shadow/*-policy.json` (one policy per file, same `id` replaces, new `id` adds) are evaluated next to the enforced policies on sampled traffic (`cosec.shadow.sample-rate`), and every `WIDENED`, `NARROWED`, or `CANDIDATE_ERROR` diff is logged under `me.ahoo.cosec.shadow`. Responses never change. Treat any `WIDENED` diff as a grant to review. Candidate app permissions go in `classpath:cosec-shadow/*-app-permission.json` and replace the enforced catalog of the same app id; role grants are not shadowed.

## Access review

To answer "who can call X?" or "what can principal Y reach?" across policies, app permissions and role grants, use `AccessAnalyzer.load(policyLocations, appPermissionLocations, roles)` (roles: app id → role → permission ids, `role@space` for a non-default space) then `whoCan(request)` (allows and denies whose action matches in the request's app and space, flagged `conditional` and `templated`) or `grantsOf(principal, appId, spaceId)`. It matches actions only and errs on the side of listing a rule; use explanations for one concrete principal and request.

## History and rollback

With `cosec.authorization.cache.history.enabled=true`, every policy save records a revision (author via `setPolicy(p).contextWrite(PolicyRevisions.author("name", "note"))`). Use the `PolicyHistory` bean to list revisions and `rollback(policyId, revision, author)`; a rollback is itself a new revision.

## Validation

The human-facing reference is https://cosec.ahoo.me/policies/language.

Validate with CoSec's runtime parser, not JSON syntax alone. In a CoSec-based test:

```kotlin
val policies = LocalPolicyLoader(setOf("classpath:cosec-policy/orders-policy.json")).policies
require(policies.isNotEmpty())
policies.forEach(DefaultPolicyEvaluator::evaluate)
```

Prefer declarative cases: a JSON suite (`policies`, optional `appPermissions`, `roles` granted in every app and `appRoles` granted in one app, with `role@space` for a non-default space, and `cases` of `principal`, `request`, `expect.result` = `ALLOW` | `EXPLICIT_DENY` | `IMPLICIT_DENY`, optionally `policyId` / `statementName` / `roleId` / `permissionId`) run with `PolicyTestKit.run("classpath:cosec-policy-test/*.json").assertPasses()`. Failures name the rule that decided instead.

Since 7.0 `LocalPolicyLoader` fails loading (and the application fails to start) on any file it cannot parse or build, on two files declaring the same policy ID, and on a configured location that matches no file (only the default `classpath:cosec-policy/*-policy.json` may match nothing); the error names the file, ID or location. Still assert that the expected policy ID loaded, which catches a file outside the configured locations. `DefaultPolicyEvaluator` catches expected regex-timeout signals but surfaces other matcher configuration errors. Add one authorization test for each meaningful allow/deny boundary.

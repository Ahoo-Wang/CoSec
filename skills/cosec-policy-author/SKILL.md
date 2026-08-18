---
name: cosec-policy-author
description: Write, review, validate, or explain CoSec policy JSON, including allow/deny precedence, action and condition matchers, tenant and role rules, and local or Redis rate limits. Do not use for Spring Boot setup.
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

Use the full field set above so both runtime deserialization and the bundled JSON Schema accept the policy. Runtime requires `id`, `name`, `type`, and `tenantId`, while the current schema requires `category`, `name`, `description`, `tenantId`, `type`, and `statements` but omits `id`. A statement requires `action`; `effect` defaults to `allow` and `condition` defaults to match-all. Local files normally live under `src/main/resources/cosec-policy/` and match `*-policy.json`.

## Evaluation semantics

Authorization evaluates in tiers: root bypass, blacklist, global policies, principal-attached policies, then role permissions. The first tier that produces a result stops evaluation.

Within a policy tier, CoSec first evaluates every matched policy-level condition, pools their statements, checks all `deny` statements, then all `allow` statements. Therefore:

- a matching deny overrides every allow in the same tier, regardless of file or array order;
- a false policy-level condition skips that policy; it does not deny the request;
- an allow in an earlier tier prevents later tiers from running, so a principal or role deny cannot override a global allow;
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

`"*"` or `{ "all": { "method": "GET" } }` matches all paths. `{ "composite": [...] }` ORs heterogeneous action matchers. Path patterns support variables such as `{id}` and SpEL templates such as `#{principal.id}`.

## Condition reference

| Type | Required configuration | Purpose |
|---|---|---|
| `all` | none | Always match |
| `authenticated` | none | Require a non-anonymous principal |
| `inRole` | `value` | Match a principal role |
| `inTenant` | `value`: `default`, `user`, or `platform` | Match tenant type, not tenant ID |
| `eq`, `contains`, `startsWith`, `endsWith` | `part`, `value` | Compare an extracted string |
| `in` | `part`, array `value` | Match one of several values |
| `regular` | `part`, `pattern` | Regex match with a time budget |
| `path` | `part`, `pattern`, optional `options` | Spring path-pattern match on an extracted value |
| `bool` | optional sibling arrays `and`, `or` | Combine conditions |
| `spel` | `expression` | Evaluate against root properties `request` and `context` |
| `ognl` | `expression` | Sandboxed expression using `#request` and `#context` |
| `rateLimiter` | `permitsPerSecond` | One in-memory limit per matcher/JVM |
| `groupedRateLimiter` | `part`, `permitsPerSecond`, `expireAfterAccessSecond` | In-memory per-part limit |
| `redisRateLimiter` | `permitsPerSecond`; optional `windowSeconds`, `strictFailure` | Cluster-wide Redis limit |
| `redisGroupedRateLimiter` | `part`, `permitsPerSecond`; optional `windowSeconds`, `strictFailure` | Cluster-wide Redis per-part limit |

The Redis types exist only when cache support and `StringRedisTemplate` activate their Spring factories. They use an atomic sliding window, return 429 when exceeded, fail open on Redis errors by default, and fail closed when `strictFailure` is `true`. The auto-configured local-policy lifecycle starts after matcher registration, so bootstrap files may use them. The in-memory limiters also return 429 when exceeded.

`negate` is handled only by matchers based on `AbstractConditionMatcher`; the ungrouped `rateLimiter` and `redisRateLimiter` do not support it. Avoid negating any limiter because an exceeded quota throws before boolean negation.

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

Use singular `request.header`, and use `request.path.var.id` only after the action matcher captures `{id}`. An unsupported part throws during evaluation.

## Security review

Check the final policy for:

- accidental broad global allows that short-circuit stricter principal or role tiers;
- deny conditions whose negation is reversed;
- missing HTTP method constraints on state-changing endpoints;
- trusted-proxy assumptions behind `request.remoteIp`;
- tenant type (`inTenant`) being confused with tenant ID (`context.tenantId`);
- unsafe or needlessly complex expressions when a built-in matcher suffices;
- local limiters used where a cluster-wide quota is required.

## Validation

Validate with CoSec's runtime parser, not JSON syntax alone. In a CoSec-based test:

```kotlin
val policies = LocalPolicyLoader(setOf("classpath:cosec-policy/orders-policy.json")).policies
require(policies.isNotEmpty())
policies.forEach(DefaultPolicyEvaluator::evaluate)
```

`LocalPolicyLoader` logs and skips malformed policies, so assert that the expected policy ID loaded. `DefaultPolicyEvaluator` catches expected rate-limit and regex-timeout signals but surfaces other matcher configuration errors. Add one authorization test for each meaningful allow/deny boundary.

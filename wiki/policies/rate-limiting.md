---
title: "Rate Limiting"
description: "Limit how often permitted requests may run, per caller, path or any other key, with rules in the policy documents."
---

# Rate Limiting

For policy authors and operators protecting APIs from overuse. Rate limit rules live in the policy documents and run
only after a request is permitted; this page covers writing rules, choosing a counter backend, and what clients see
when a limit is hit.

A policy decides whether a request is allowed; a rate limit rule decides how often permitted requests may pass. The
rules are *obligations* of a permit: a request that is denied or cannot be decided spends nothing, so it never uses up
the quota of legitimate callers. [Checks in code](../integration/checks.md), `simulate`, `explain` and shadow
evaluations spend nothing either.

```mermaid
flowchart LR
    A[Request] --> B[Decision]
    B -->|Deny| D[401 / 403]
    B -->|Indeterminate| U[503]
    B -->|Permit| C{Matching<br>rate limit rules}
    C -->|none| E[Allowed]
    C -->|permits left| E
    C -->|a rule exhausted| F[429]
    C -->|strict rule cannot count| U
```

## Rules

Every document can hold rules in `rateLimits` ([fields](./language.md#rate-limit-rules)):

```json
{
  "schema": 2,
  "revision": 7,
  "publishedAt": "2026-11-01T08:00:00Z",
  "policies": [],
  "bindings": [],
  "rateLimits": [
    {
      "id": "login-per-ip",
      "action": "http:POST",
      "resource": "http:path:/auth/login",
      "key": "environment.ip",
      "permitsPerSecond": 0.1,
      "windowSeconds": 60,
      "strictFailure": true
    },
    {
      "id": "export-free-tier",
      "action": "http:GET",
      "resource": "http:path:/export/**",
      "condition": { "in": ["free", "scope.roles"] },
      "key": "subject.id",
      "permitsPerSecond": 0.2,
      "windowSeconds": 60
    }
  ]
}
```

A rule matches a permitted request the way a DENY statement would: one of its action patterns, one of its resource
patterns, and its condition. A window allows `permitsPerSecond × windowSeconds` requests, and at least one. A route in a
service's [route map](../integration/web.md#route-maps) is not an `http` request, so a rule on `http:` and a path does
not limit it: write the rule for the route's action too
([Authorizing business actions](../scenarios/route-maps.md#a-request-has-one-action)).

Which rules a request is held to:

- the platform document's rules, for every permitted request;
- the app document's rules, when the request read that document: it has an app, and the principal holds a role in the
  request's space or the tenant's document binds one of the app's permission policies to `Anyone` or to a principal
  ([Role permissions](./role-permissions.md#app-documents));
- the tenant document's rules, for requests of that tenant.

They run in that order, platform first, then app, then tenant, and in document order within a document. Each matching
rule spends one permit. The first rule whose bucket is exhausted answers `429`; the rules after it spend nothing, and
the rules before it keep what they spent. Because the platform's rules run first, a platform rule bounds a request
before any app or tenant rule spends anything. Rules add up: a tenant or app rule never replaces or relaxes a platform
rule, even one with the same id, so a request passes only while every rule it matches has permits left.

Every rule a permitted request matches costs one call to the limiter, made one after another: with the Redis limiter,
one round trip each. So a document holds at most 64 rules (`DOC-6`): a document with more does not compile, and the
control plane answers a change that would make one with a `COMPILE` finding, `422`. Keep the rules a request matches
few; one rule with a `key` replaces many rules that differ only in what they count.

## Keys

`key` names the attribute whose value picks the bucket: a String or Ip attribute of the
[catalog](./language.md#attributes), such as `environment.ip`, `subject.id`, `scope.tenant`,
`subject.attributes.plan` or `resource.var.orderId`. A `resource.var.<name>` key must be captured by every resource
pattern of the rule; when several match, the value is the first pattern's, in written order, under whose captures the
rule's condition holds ([path variables](./language.md#path-variables)).

| `key` | Buckets |
|---|---|
| absent | One bucket shared by every request the rule matches. |
| present | One bucket per value. Requests where the value is absent share one bucket of their own. |

A value is its bucket as it is, `k:<value>`, which every limiter writes as UTF-8. A value with an unpaired UTF-16
surrogate (a JSON escape such as `"\uD800"` in a token attribute) has no UTF-8 encoding: its bucket is `e:<value>`
with each unpaired surrogate as its `\uXXXX` escape and each `\` doubled, so it never counts in the bucket of the
value with `?` in its place (`IDN-10`). A limiter of your own gets only buckets that are well-formed text.

Rules of different documents never share buckets: a rule counts under its document and its id, `platform/<id>`,
`app:<appId>/<id>` or `tenant:<tenantId>/<id>`
([`CompiledRateLimit.counterId`](https://github.com/Ahoo-Wang/CoSec/blob/main/core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/RateLimits.kt)).
A rule id cannot contain `:` or `/`, so no rule can name another document's counter: a tenant cannot spend, or reset,
the platform's quotas.

A key that reads `subject.id` gives anonymous callers one shared bucket; key them by `environment.ip` in a separate
rule. A key or condition that reads an [external attribute](./language.md#external-attributes-and-onmissing) which
cannot be fetched leaves the rule without a bucket: with `onMissing: "nomatch"` (platform rules only) it is skipped,
otherwise `strictFailure` decides as for a [failing limiter](#failure-behavior).

## Counting and backends

Both limiters count with the same sliding window: the estimate is the previous window's count, weighted by how much
of it still overlaps, plus the current window's count. A request is allowed while the estimate is below the limit;
refused requests are not counted.

| Limiter | Used when | Scope |
|---|---|---|
| `ReactiveRedisRateLimiter` | `cosec-cocache` is on the classpath and a `ReactiveStringRedisTemplate` bean exists | Cluster-wide, checked and counted atomically by a Lua script, without blocking the caller's thread. Counters live under `<cosec.key-prefix>:rate-limiter:{<rule>:<bucket>}:<window>` and expire after two windows. The braces are a Redis Cluster hash tag: the two windows of a bucket, which the script reads together, are on one slot, and different buckets spread over the cluster. |
| `LocalRateLimiter` | otherwise | Per JVM: with N instances the effective limit is N times higher. Keeps at most 100,000 buckets and drops buckets idle for an hour; a dropped bucket starts again from zero. |

Your own `RateLimiter` bean replaces both ([Extending](../architecture/extending.md)). Each call has
`cosec.authorization.rate-limit.timeout` (default `500ms`, [Configuration](../operations/configuration.md)); a call
that takes longer counts as a failure.

## Responses and headers

A request stopped by a rule is answered `429 Too Many Requests` with the `too-many-requests` problem
([status codes](../integration/web.md)). A permitted request that spent at least one permit carries the headers of the
rule with the fewest permits left; a `429` carries those of the exhausted rule:

| Header | Value |
|---|---|
| `RateLimit-Limit` | Permits per window of that rule, rounded up. |
| `RateLimit-Remaining` | How many more requests its current window would allow right now, rounded up: a part of a permit left still admits one. |
| `RateLimit-Reset` | Seconds until its current window ends, at least `1`. |
| `Retry-After` | Only on `429`: the same as `RateLimit-Reset`. |

A request that matched no rule, or whose rules were all skipped, has none of these headers.

## Failure behavior

A rule cannot be counted when the limiter fails (Redis unreachable, a script error), times out, is not configured, or
the rule's bucket cannot be read. Its `strictFailure` decides:

| `strictFailure` | The request |
|---|---|
| `false` (default) | The rule is skipped; the next rule runs. Logged at `WARN`. |
| `true` | Refused with `503 Service Unavailable` (`unavailable` problem, no `Retry-After`). Logged at `ERROR`. |

A bucket that cannot be read for any reason other than a missing external attribute refuses the request even without
`strictFailure`. Failure logs are throttled to one per ten seconds. Skipping a lenient rule is one of the documented
exceptions to [fail closed](../guide/security-model.md); set `strictFailure` on rules that protect against abuse, such
as login attempts, and leave it off on rules that only smooth load.

## The control API

The [control API](./testing.md#the-control-plane) is served behind the pipeline, so a platform rule limits it like any
other path, and keyed by `scope.tenant` it gives each tenant a bucket of its own. Cover the routes that compile,
evaluate or check: `simulate`, `explain`, `analyze` and `changes`, under `cosec.control.http.base-path` and its `/v1`:

```json
{
  "id": "control-per-tenant",
  "action": "http:POST",
  "resource": [
    "http:path:/cosec/control/v1/simulate",
    "http:path:/cosec/control/v1/explain",
    "http:path:/cosec/control/v1/analyze",
    "http:path:/cosec/control/v1/changes",
    "http:path:/cosec/control/v1/changes/*"
  ],
  "key": "scope.tenant",
  "permitsPerSecond": 0.5,
  "windowSeconds": 60
}
```

- The rule belongs in the platform document: a tenant's own rules count in their own buckets and never relax it.
- The bucket is the caller's tenant, counted after the pipeline permits the request and before the control plane
  authorizes it: every principal of the tenant that the pipeline lets onto these paths spends it, administrator or not,
  and every `(platform)` principal spends the platform's. Permit the control paths in the pipeline only to principals
  bound to a control role, so that no other principal can hold off a tenant's administrators.
- An exhausted bucket answers `429` before the control API does any work. The control API serves only these exact
  paths: one with a trailing `/` or an empty segment (`//`) is a `404`, so the patterns cover every request it serves.
- Leave the break-glass routes (`/break-glass` and below) out of it: give them a rule of their own, or none, so that
  a spent bucket never holds off an emergency revoke. `changes/*` still covers three steps of break-glass: the
  `:publish` that makes an approved break-glass grant take effect, the `:publish` that recovers a stalled revoke, and
  `:abandon` of a break-glass change. Give the platform's bucket room for them, or keep the platform's principals out
  of the rule with `"condition": {"not": {"eq": ["scope.tenant", "(platform)"]}}`.
- `documents/{key}:apply` and `:rollback` propose changes too, and can join the rule.

No such rule is published for you, by `cosec-migrate bootstrap` or otherwise: the limits depend on the deployment's
size.

## Publishing rules

Rules are part of their documents, so they are checked and compiled with them: an invalid id, a duplicate id in one
document, `permitsPerSecond` not above `0`, `windowSeconds` below `1`, a `key` that is not a String or Ip attribute,
or a variable a pattern does not capture fails the whole document ([Testing policies](./testing.md#validating-policies)).

Change rules through the control plane like any other entry ([Changes](./testing.md#changes)):

- `putRateLimit` (`rateLimit`, optional `ifMatch`) adds a rule, or replaces the one with its id where it stands;
  `removeRateLimit` (`id`, optional `ifMatch`) removes one;
- `POST /documents/{key}:apply` proposes them from a whole document: `MERGE` puts new and changed rules, `REPLACE` also
  removes the rules the document leaves out. Order is part of the rules (the first exhausted rule answers), so a
  rule out of place is removed and put again; a rollback restores the rules of its revision the same way.

Each operation needs `cosec:write` on the entry `doc/<document>/rateLimit/<id>`, such as
`doc/tenant/acme/rateLimit/export-per-user`. A tenant's rule changes, tightening or loosening, need no second person:
its rules count in its own buckets, and every platform and app rule still applies to its requests. A rule change to the
platform or app document, removals included, needs [approval](./testing.md#approval) as any other change there does.

A shadow window compares decisions, and a rule never changes a decision, so a change that touches only rate limit rules
shows no difference in its window: the window tells you nothing about the quota.

**Next:** catch mistakes before they reach production, in [Testing policies](./testing.md).

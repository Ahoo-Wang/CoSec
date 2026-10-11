# Policies

A policy is a JSON document that grants or denies access. This page is the reference for the policy
language: structure, actions, conditions, how policies combine, and role-based app permissions.

JSON Schemas for editors and CI live in [`schema/`](https://github.com/Ahoo-Wang/CoSec/tree/main/schema):
`cosec-policy.schema.json` and `cosec-app-permission.schema.json`.

## Structure

```json
{
  "id": "order-service",
  "name": "Order service",
  "category": "order",
  "description": "Who may read and change orders",
  "type": "custom",
  "tenantId": "(platform)",
  "condition": { "authenticated": {} },
  "statements": [
    { "name": "read", "action": { "path": { "pattern": "/orders/**", "method": "GET" } } },
    { "name": "no-delete", "effect": "deny", "action": { "path": { "pattern": "/orders/*", "method": "DELETE" } } }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Unique policy id. Principals reference non-global policies by this id. |
| `name`, `category`, `description` | yes | Descriptive metadata. |
| `type` | yes | `global`: evaluated for every request. `system` / `custom`: evaluated only for principals whose `policies` include this id. |
| `tenantId` | yes | Owning tenant (`(platform)` for platform-wide policies). |
| `condition` | no | Policy-level condition. If it does not match, the whole policy is skipped. |
| `statements` | yes | The rules. |

Enum values (`type`, `effect`) are case-insensitive.

### Statement

| Field | Required | Default | Meaning |
|---|---|---|---|
| `name` | no | `""` | Shown in logs, traces and audit events. |
| `effect` | no | `allow` | `allow` or `deny`. |
| `action` | yes | | Which requests the statement covers. |
| `condition` | no | always | When the statement applies. |

A statement **matches** when its action and its condition both match. A matched `allow` yields
`ALLOW`; a matched `deny` yields `EXPLICIT_DENY`.

## Actions

An action selects requests, usually by path. It can be written four ways:

| Form | Example | Meaning |
|---|---|---|
| `"*"` | `"action": "*"` | Every request. |
| string | `"action": "/orders/{id}"` | One path pattern, any method. |
| array | `"action": ["/a/**", "/b"]` | Any of several actions; elements may be strings or objects. |
| object | `"action": { "path": { ... } }` | A typed action matcher with options. |

The `path` action matcher:

```json
{
  "path": {
    "pattern": ["/orders/{id}", "/orders/{id}/items"],
    "method": ["GET", "HEAD"],
    "options": { "caseSensitive": true, "separator": "/", "decodeAndParseSegments": true }
  }
}
```

- `pattern`: a string or array of Spring [`PathPattern`](https://docs.spring.io/spring-framework/reference/web/webflux/controller/ann-requestmapping.html#webflux-ann-requestmapping-uri-templates)s
  (`*`, `**`, `{var}`, `{*rest}`).
- `method`: optional; a string or array of HTTP methods. Omitted means any method.
- `options`: optional; how paths are split and compared. The separator also makes non-HTTP
  "paths" possible, such as `order.read` with `"separator": "."`.

**Path variables.** Variables captured by the matching action are available to conditions as
`request.path.var.<name>`.

**Templated patterns.** A pattern containing `#{...}` is a SpEL template evaluated against the security
context, so a pattern can depend on the caller. For example, `"/users/#{principal.id}/**"` lets each
user reach only their own resources.

## Conditions

A condition is a single-key object: the key is the condition type, the value is its configuration.

```json
"condition": { "eq": { "part": "context.tenantId", "value": "(platform)" } }
```

Every condition accepts `"negate": true` to invert its result.

### Context conditions

| Type | Configuration | Matches when |
|---|---|---|
| `all` | `{}` | always |
| `authenticated` | `{}` | the principal is not anonymous |
| `inRole` | `{ "value": "admin" }` | the principal has the role |
| `inTenant` | `{ "value": "default" \| "user" \| "platform" }` | the tenant is the default tenant `(0)`, a user tenant, or the platform tenant `(platform)` |

### Value conditions

These read one **part** of the request or security context and compare it.

| Type | Configuration | Matches when the part … |
|---|---|---|
| `eq` | `part`, `value`, `ignoreCase` | equals `value` |
| `in` | `part`, `value` (array) | is one of `value` |
| `contains` | `part`, `value`, `ignoreCase` | contains `value` |
| `startsWith` | `part`, `value`, `ignoreCase` | starts with `value` |
| `endsWith` | `part`, `value`, `ignoreCase` | ends with `value` |
| `regular` | `part`, `pattern`, `timeout` (ms, default `1000`) | matches the regular expression |
| `path` | `part`, `pattern`, `options` | matches the path pattern |

`regular` runs under a time budget as a ReDoS guard. Exceeding it denies the request (`403`); it is
never treated as a match.

**Parts:**

| Part | Value |
|---|---|
| `request.path`, `request.method`, `request.remoteIp` | path, HTTP method, client IP (see [remote IP](./configuration.md#authorization)) |
| `request.appId`, `request.spaceId`, `request.deviceId` | `CoSec-App-Id`, `CoSec-Space-Id`, `CoSec-Device-Id` header (or query parameter) |
| `request.origin`, `request.origin.host`, `request.referer`, `request.referer.host` | `Origin` / `Referer` and their hosts |
| `request.header.<name>` | any request header |
| `request.attributes.<name>` | request attributes, e.g. `ipRegion` from [IP geolocation](./operations.md#ip-geolocation) or the gateway route id |
| `request.path.var.<name>` | a path variable captured by the matched action |
| `context.tenantId`, `context.principal.id` | tenant and principal id |
| `context.principal.attributes.<name>` | a principal attribute (a JWT `attributes` claim entry) |

### Composing conditions

`bool` combines conditions: every `and` condition must match, and if `or` is present at least one
of them must match too.

```json
"condition": {
  "bool": {
    "and": [ { "authenticated": {} } ],
    "or":  [ { "inRole": { "value": "admin" } },
             { "eq": { "part": "request.header.X-Team", "value": "ops" } } ]
  }
}
```

### Expressions

| Type | Configuration | Notes |
|---|---|---|
| `spel` | `expression` | Spring Expression Language, read-only; the root object exposes `request` and `context`. |
| `ognl` | `expression` | OGNL in a sandbox: read-only navigation of `request` and `context`; no mutators, reflection, object construction or type references. |

Prefer the declarative conditions above. Expressions are harder to review and test.

### Rate limits

A rate-limit condition matches while there are permits left. When the limit is exhausted the request
is rejected with `429 Too Many Requests`.

| Type | Configuration | Scope |
|---|---|---|
| `rateLimiter` | `permitsPerSecond` | per application instance |
| `groupedRateLimiter` | `part`, `permitsPerSecond`, `expireAfterAccessSecond` | per instance, one limiter per value of `part` (e.g. per user) |
| `redisRateLimiter` | `permitsPerSecond`, `windowSeconds` (default `1`), `strictFailure` (default `false`) | cluster-wide (Redis, sliding window) |
| `redisGroupedRateLimiter` | the above plus `part` | cluster-wide, per value of `part` |

Redis limiters need `cache-support`. With `strictFailure: false`, a Redis outage lets requests
through rather than blocking all traffic; set it to `true` to fail closed.

```json
{ "name": "login-throttle",
  "action": { "path": { "pattern": "/auth/login", "method": "POST" } },
  "condition": { "redisGroupedRateLimiter": { "part": "request.remoteIp", "permitsPerSecond": 5 } } }
```

## How policies combine

For each request, CoSec evaluates three layers:

1. **Global policies**: every `global` policy.
2. **Principal policies**: the policies listed in the principal's `policies`.
3. **Role permissions**: permissions granted to the principal's roles for the request's app and space.

Within each layer, and across all layers, **deny wins**: if any matched statement or permission is a
`deny`, the result is `EXPLICIT_DENY`, even if another layer allowed the request. Otherwise any
matched `allow` grants access. If nothing matched, the result is `IMPLICIT_DENY`.

Two principals bypass this: the **root** principal (id `cosec`, configurable, see
[Configuration](./configuration.md#authorization)) is always allowed, and a blacklisted request is
always denied.

::: tip Policy conditions run first
A policy's own `condition` is evaluated once, before its statements. A rate limit placed there
consumes a permit for every request the policy applies to, whichever statement decides.
:::

## App permissions and roles

Policies attach rules to principals directly. For role-based access control, describe an application's
permissions once and grant them to roles.

```json
{
  "id": "order-app",
  "condition": { "authenticated": {} },
  "groups": [
    {
      "name": "orders",
      "description": "Order management",
      "permissions": [
        { "id": "order.read",   "name": "Read orders",   "action": { "path": { "pattern": "/orders/**", "method": "GET" } } },
        { "id": "order.manage", "name": "Manage orders", "action": "/orders/**" },
        { "id": "order.export.block", "name": "No exports", "effect": "deny", "action": "/orders/export" }
      ]
    }
  ]
}
```

- A permission is a statement with an `id` (plus optional `name` and `description`).
- The app's `condition` gates all of its permissions.
- The request selects the app with the `CoSec-App-Id` header and the space with `CoSec-Space-Id`.
- A role is granted a **set of permission ids** per space; `*` grants every permission of the app.

With `cache-support`, CoSec reads these from Redis (default key prefix `cosec`):

| Data | Redis key | Value |
|---|---|---|
| App permission | `cosec:app:permission:<appId>` | the JSON above |
| Role grants | `cosec:role:permission:<roleId>` (default space) or `cosec:role:permission:<roleId>@<spaceId>` | a set of permission ids |

CoSec reads permissions and grants; your administration service owns writing them.

## Loading and generating policies

- **Local files**: with `cosec.authorization.local-policy.enabled=true`, policies are loaded from
  `classpath:cosec-policy/*-policy.json`. With `init-repository=true` they are written to the
  `PolicyRepository` on startup (existing ids are kept unless `force-refresh=true`).
- **At runtime**: write policies through `PolicyRepository.setPolicy`. The Redis-backed repository
  validates a policy before storing it, and every instance sees the change.
- **From OpenAPI**: with `openapi-support`, the actuator endpoints `cosecPolicyGenerator` and
  `cosecAppPermissionGenerator` generate a policy or app permission from your API description as a
  starting point. See [Operations](./operations.md#openapi).

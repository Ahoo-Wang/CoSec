# Policies

A policy is a JSON document that grants or denies access. This page is the reference for the policy
language: structure, actions, conditions, how policies combine, role-based app permissions, and how
policies are loaded and checked.

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
| `name` | yes | Display name. |
| `type` | yes | `global`: evaluated for every request. `system` / `custom`: evaluated only for principals whose `policies` include this id. |
| `tenantId` | yes | Owning tenant (`(platform)` for platform-wide policies). |
| `category`, `description` | no | Descriptive metadata. |
| `condition` | no | Policy-level condition. If it does not match, the whole policy is skipped. |
| `statements` | no | The rules. A policy without statements never matches. |

Enum values (`type`, `effect`) are case-insensitive.

### Statement

| Field | Required | Default | Meaning |
|---|---|---|---|
| `name` | no | `""` | Shown in logs, traces and audit events. |
| `effect` | no | `allow` | `allow` or `deny`. |
| `action` | yes | | Which requests the statement covers. |
| `condition` | no | always | When the statement applies. |

A statement **matches** when its action matches and then its condition matches. A matched `allow` yields
`ALLOW`; a matched `deny` yields `EXPLICIT_DENY`.

## Actions

An action selects requests, usually by path. It can be written in these forms:

| Form | Example | Meaning |
|---|---|---|
| `"*"` | `"action": "*"` | Every request. |
| string | `"action": "/orders/{id}"` | One path pattern, any method. |
| array | `"action": ["/a/**", { "path": { ... } }]` | Any of several actions; elements are strings or objects. |
| `path` | `"action": { "path": { ... } }` | Path patterns with methods and options (below). |
| `all` | `"action": { "all": { "method": "GET" } }` | Every path, optionally limited to some methods. |
| `composite` | `"action": { "composite": [ ... ] }` | Same as the array form. |

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
- `method`: optional; a string or array of HTTP methods, case-insensitive. Omitted means any method.
- `options`: optional; how paths are split and compared. The separator also makes non-HTTP
  "paths" possible, such as `order.read` with `"separator": "."`.

**Path variables.** Variables captured by the matching action are available to the statement's condition
as `request.path.var.<name>`.

## Templates

A pattern or value containing `#{...}` is a read-only SpEL template evaluated against the security context,
so a rule can depend on the caller. The template root exposes `principal` (with `id`, `roles`,
`attributes`) and `tenant` (with `tenantId`).

Templates are accepted in two places:

- **path patterns**: `"/users/#{principal.id}/**"` lets each user reach only their own resources;
- **the `eq` condition's `value`**: compare a request part with the caller, as below.

```json
{ "name": "own-orders",
  "action": "/users/{userId}/orders/**",
  "condition": { "eq": { "part": "request.path.var.userId", "value": "#{principal.id}" } } }
```

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

`inTenant` checks the *kind* of tenant. To check a specific tenant id, use
`{ "eq": { "part": "context.tenantId", "value": "..." } }`.

### Value conditions

These read one **part** of the request or security context and compare it.

| Type | Configuration | Matches when the part … |
|---|---|---|
| `eq` | `part`, `value` ([template](#templates) allowed), `ignoreCase` | equals `value` |
| `in` | `part`, `value` (array) | is one of `value` |
| `contains` | `part`, `value`, `ignoreCase` | contains `value` |
| `startsWith` | `part`, `value`, `ignoreCase` | starts with `value` |
| `endsWith` | `part`, `value`, `ignoreCase` | ends with `value` |
| `regular` | `part`, `pattern`, `timeout` (ms, default `1000`) | matches the regular expression as a whole, ignoring case |
| `path` | `part`, `pattern`, `options` | matches the path pattern |

`ignoreCase` defaults to `false`. `regular` runs under a time budget as a ReDoS guard. Exceeding it denies
the request (`403`); it is never treated as a match.

**Parts:**

| Part | Value |
|---|---|
| `request.path`, `request.method`, `request.remoteIp` | path, HTTP method, client IP (see [client IP](./web-integration.md#client-ip-behind-proxies)) |
| `request.appId`, `request.spaceId`, `request.deviceId` | `CoSec-App-Id`, `CoSec-Space-Id`, `CoSec-Device-Id` header (or query parameter) |
| `request.origin`, `request.origin.host`, `request.referer`, `request.referer.host` | `Origin` / `Referer` and their hosts |
| `request.header.<name>` | any request header |
| `request.attributes.<name>` | a [request attribute](./web-integration.md#request-attributes), such as `ipRegion` |
| `request.path.var.<name>` | a path variable captured by the matched action |
| `context.tenantId`, `context.principal.id` | tenant and principal id |
| `context.principal.attributes.<name>` | a principal attribute (a JWT `attributes` claim entry) |

A part that is absent from the request reads as an empty string. Any other part name is a policy error:
it is not detected when the policy loads, and every request that reaches the condition fails with `500`.

Headers, `request.remoteIp` and request attributes are only as trustworthy as their source. Never
base a decision on a header the client can set unless a trusted proxy overwrites it.

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

| Type | Configuration | Evaluated against |
|---|---|---|
| `spel` | `expression` | Spring Expression Language, read-only. The root object has the properties `request` and `context`, e.g. `context.principal.attributes['tier'] == 'gold'`. |
| `ognl` | `expression` | OGNL in a sandbox. The root object is the request; the security context is the variable `#context` (the request is also `#request`). No mutators, reflection, object construction or type references. |

Prefer the declarative conditions above. Expressions are harder to review and test.

::: tip Rate limits are not conditions
Policies decide whether a request is allowed. How often allowed requests may pass is set by separate
[rate limit rules](./rate-limiting.md).
:::

## How policies combine

For each request, CoSec evaluates three layers:

1. **Global policies**: every `global` policy.
2. **Principal policies**: the policies listed in the principal's `policies`.
3. **Role permissions**: permissions granted to the principal's roles for the request's app and space.

Within each layer, and across all layers, **deny wins**: if any matched statement or permission is a
`deny`, the result is `EXPLICIT_DENY`, even if another layer allowed the request. Otherwise any
matched `allow` grants access. If nothing matched, the result is `IMPLICIT_DENY`. The order of policies and
statements never changes the result, only which matching rule is reported.

Two checks come before the layers: the **root** principal (id `cosec`, configurable, see
[Configuration](./configuration.md#authorization)) is always allowed, and a request rejected by the
blacklist is always denied.

A policy's own `condition` is evaluated once, before its statements.

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
  `PolicyRepository` on startup (existing ids are kept unless `force-refresh=true`). Authorization only reads
  the repository, so without `init-repository` local files have no effect.
- **At runtime**: write policies through `PolicyRepository.setPolicy`. The Redis-backed repository
  validates a policy before storing it, and every instance sees the change.
- **From OpenAPI**: with `openapi-support` and Spring Boot Actuator,
  `GET /actuator/cosecPolicyGenerator` generates a policy and `GET /actuator/cosecAppPermissionGenerator`
  an app permission from your OpenAPI description: one `allow` entry per operation (path and method), with a
  placeholder id and metadata. Use them as starting points: set the ids, narrow the rules, and review
  before deploying.

::: warning Invalid files are skipped
A local policy file that cannot be parsed (invalid JSON, a missing required field, an unknown condition
type, a bad matcher configuration) is logged at `ERROR` and skipped; the application still starts. Check
the startup log, or validate policies in a test as below.
:::

## Validating policies

Validate policies in a unit test with the runtime parser, not only the JSON Schema:

```kotlin
val policies = LocalPolicyLoader(setOf("classpath:cosec-policy/*-policy.json")).policies
check(policies.any { it.id == "order-service" })   // the file was not skipped
policies.forEach(DefaultPolicyEvaluator::evaluate)  // matchers run against a sample request
```

`DefaultPolicyEvaluator` runs every condition and action once against a sample request, so configuration
errors that only show at request time (such as an unknown part) fail the test. Add one authorization test
per allow/deny boundary that matters, using `SimpleAuthorization` with in-memory repositories.

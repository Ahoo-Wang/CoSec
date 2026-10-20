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

### Time conditions

`time` matches inside a validity period and, optionally, a recurring daily window. It reads the server clock,
never the request.

| Configuration | Meaning |
|---|---|
| `notBefore`, `notAfter` | The period, as ISO date-times; `notAfter` is exclusive. A date-time without an offset is read in `zone`. |
| `daysOfWeek` | The days the window applies, e.g. `["MON", "TUE", "WED", "THU", "FRI"]` (full names work too). |
| `from`, `to` | A daily window `[from, to)` as `HH:mm`. When `from` is after `to` the window crosses midnight, and its early-morning part belongs to the day it started on. |
| `zone` | Time zone of the window and of offset-less date-times, default `UTC`. |

At least one of the period, `daysOfWeek` or `from`/`to` is required. A grant that should expire on its own is a
statement with `notAfter`:

```json
{ "name": "temporary-support-access",
  "action": "/orders/**",
  "condition": { "bool": { "and": [
    { "inRole": { "value": "support" } },
    { "time": { "notAfter": "2026-10-20T00:00:00", "zone": "Asia/Shanghai" } } ] } } }
```

`negate` turns a window into "outside the window", for example to deny writes outside business hours.

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
| `resource.<name>` | an attribute of the target object, set by a check in code; empty in the request filter (see [Object-level rules](#object-level-rules)) |
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

Two checks come before the layers: a request rejected by the blacklist is always denied, and then the
**root** principal is allowed. Root is off by default; when enabled, it is the platform tenant's principal with id
`cosec` (configurable, see [Configuration](./configuration.md#authorization)).

A policy's own `condition` is evaluated once, before its statements.

## Object-level rules

Whether a user may change *this* order depends on the order: its owner, tenant or status. Those live in your
database, and the request filter runs before your handler has loaded anything, so CoSec decides object-level
rules in two steps:

1. **In the request filter** the route is authorized as usual. The resource is unknown there, so every
   `resource.<name>` part is empty.
2. **In your code**, after loading the object, an [`AuthorizationChecker`](./web-integration.md#checking-permissions-in-code)
   check passes its attributes as `resource`, and conditions read them as `resource.<name>`.

Write object rules as **denies that apply only when the resource is known**. The same path then passes the filter
and is denied in the check for anyone but the owner:

```json
{ "name": "only-own-orders",
  "effect": "deny",
  "action": "/orders/**",
  "condition": { "bool": { "and": [
    { "eq": { "part": "resource.ownerId", "value": "", "negate": true } },
    { "eq": { "part": "resource.ownerId", "value": "#{principal.id}", "negate": true } } ] } } }
```

```kotlin
val order = orderRepository.findById(id)
val allowed = checker.check(
    securityContext,
    CheckRequest("/orders/$id", "PUT", resource = mapOf("ownerId" to order.ownerId)),
).awaitSingle()
if (!allowed) throw AccessDeniedException()
```

An `allow` that needs a resource part would deny the route in the filter, where the part is empty. Keep allows on
what the request itself carries, and put object conditions on denies. The check must run in your code: a handler
that skips it is not protected by object rules.

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
- A role is granted a **set of permission ids** in one app and one space; `*` grants every permission of that
  app.
- The request selects the app with the `CoSec-App-Id` header and the space with `CoSec-Space-Id`
  ([App and space](./web-integration.md#app-and-space)).

### Role grant scope

A grant belongs to an **(app, role, space)** triple and applies nowhere else:

- **App.** A grant made in one app never applies in another, even when both apps define the same permission
  id or the grant is `*`. Granting a role in two apps takes two grants.
- **Space.** The principal's roles say which space each role belongs to: `editor` is a role in the default
  space, `editor@team-a` a role in `team-a` only ([roles in tokens](./authentication.md#tokens)). A request in
  a space is evaluated with only the principal's roles in that space, so changing `CoSec-Space-Id` cannot pick
  up another space's grants. A role written with more than one `@`, or with an empty role or space, applies
  nowhere.

With `cache-support`, CoSec reads these from Redis (default key prefix `cosec`):

| Data | Redis key | Value |
|---|---|---|
| App permission | `cosec:app:permission:<appId>` | the JSON above |
| Role grants | `cosec:app:role:permission:<appId>:<roleId>` (default space) or `cosec:app:role:permission:<appId>:<roleId>@<spaceId>` | a set of permission ids |

The first `:` after the prefix ends the app id and the first `@` after it ends the role id, so an app id must
not contain `:` and a role id must not contain `@`. `AppSpacedRoleId` checks both when you write a grant; an app
whose id contains `:` holds no grants. CoSec reads permissions and grants; your administration service owns
writing them, through `RolePermissionCache` so that every instance drops its local copy:

```kotlin
rolePermissionCache[AppSpacedRoleId(appId = "order-app", roleId = "clerk", spaceId = "team-a")] =
    setOf("order.read")
```

Keys of 6.x (`cosec:role:permission:<roleId>[@<spaceId>]`, without the app) are never read; migrate them with
`RolePermissionKeyMigration` ([Upgrading](./upgrading.md#migrating-role-grants)).

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

### Policy history and rollback

Redis holds only the current version of each policy. With `cosec.authorization.cache.history.enabled=true`,
every save through the repository also records a revision: an increasing number, the time, the author, a note and
the full policy, newest first, up to `max-revisions` per policy. Pass the author through the Reactor context:

```kotlin
policyRepository.setPolicy(policy)
    .contextWrite(PolicyRevisions.author("alice", "TICKET-42: allow exports"))
```

The `PolicyHistory` bean reads and restores revisions. A rollback saves the old content as a new revision, so the
history only grows and records who rolled back:

```kotlin
policyHistory.history("orders")                 // newest first
policyHistory.rollback("orders", revision = 3, author = "bob")
```

Saves without an author are recorded as `unknown`. The policy is written before its revision, so the history
never shows a version that was not applied.

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

## Trying changes in shadow mode

A test shows what a policy change does to the requests you thought of. Shadow mode shows what it does to real
traffic, before it takes effect. Put the candidate versions of your policies in shadow files, and CoSec
evaluates every sampled request twice: once with the enforced policies, whose decision is the one returned,
and once with the candidates laid over them. Every request on which the two disagree is reported.

```yaml
cosec:
  shadow:
    enabled: true
    locations: classpath:cosec-shadow/*-policy.json   # default
    sample-rate: 0.1
```

A shadow file holds one policy, in the same format as a local policy file. A candidate replaces the enforced
policy with the same `id` everywhere that policy applies. A candidate with a new `id` is added: as a global
policy if its type is `global`, otherwise for principals that list it. To try removing a policy, give its
candidate no statements.

App permissions can be shadowed too: a file matching `classpath:cosec-shadow/*-app-permission.json` (one app
permission per file) replaces the enforced app permission with the same app `id`, its catalog and its condition.
Role grants are kept as enforced, so a grant naming a permission the candidate removed grants nothing. Role grants
themselves are not shadowed.

| Diff | Meaning |
|---|---|
| `WIDENED` | Denied today, allowed by the candidates: the change grants access. Review these before enforcing. |
| `NARROWED` | Allowed today, denied by the candidates: the change revokes access. |
| `CANDIDATE_ERROR` | A candidate failed to evaluate, for example a matcher threw. Enforced, it would deny with an error. |
| `RULE_CHANGED` | Same result, decided by a different rule. Reported only with `report-rule-changes: true`. |

Each diff carries the request (id, tenant, principal, app, method, path) and both outcomes with the rule that
decided them. By default diffs are logged under `me.ahoo.cosec.shadow`: access changes at `WARN`, rule changes at
`INFO`. Declare a `ShadowEventSink` bean to send them elsewhere, or a `ShadowPolicyProvider` bean to supply
candidates from somewhere other than files.

Shadow mode never changes a response, and its errors are only logged. It does cost a second evaluation on every
sampled request, so keep `sample-rate` low on busy services. Candidates run on a separate security context marked
as a policy evaluation, so custom matchers can skip side effects, and no rate limit is spent. A shadow file that
cannot be read stops the application from starting, because a candidate left out would report "no difference" for
a change it never evaluated. Once the diffs look right, move the candidates into the enforced policies.

### Policy test cases

Write the decisions you rely on as data, and run them in CI. A test suite is a JSON file that names the policies
(and optionally app permissions and role grants) and lists cases: a principal, a request, and the expected
result, optionally with the rule that must decide.

```json
{
  "name": "orders",
  "policies": ["classpath:cosec-policy/*-policy.json"],
  "appPermissions": ["classpath:cosec-policy/*-app-permission.json"],
  "roles": { "shipper": ["manage.order.ship"] },
  "cases": [
    { "name": "users read orders",
      "principal": { "id": "alice" },
      "request": { "path": "/orders/42" },
      "expect": { "result": "ALLOW", "policyId": "orders", "statementName": "read-orders" } },
    { "name": "internal orders are denied",
      "principal": { "id": "alice" },
      "request": { "path": "/orders/internal/1" },
      "expect": { "result": "EXPLICIT_DENY" } }
  ]
}
```

```kotlin
@Test
fun policies() = PolicyTestKit.run("classpath:cosec-policy-test/*.json").assertPasses()
```

`principal` and `request` take the same fields as [explaining a decision](./troubleshooting.md#explain-a-decision),
and `expect.result` is `ALLOW`, `EXPLICIT_DENY` or `IMPLICIT_DENY`. Each case runs through the explainer against
in-memory repositories, so a failure names the rule that decided instead and any warning, such as a policy id the
principal lists but no file defines. Policy files that cannot be read fail the run instead of being skipped.

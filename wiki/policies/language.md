---
title: "Policy Language"
description: "Every field of a CoSec policy: statements, actions, path variables, templates, conditions and expressions, and how policy files load."
---

# Policy Language

For policy authors who need the exact meaning of a field. This page is the reference for the policy JSON format.
How policies combine is explained in the [security model](../guide/security-model.md#evaluation-order), in one
sentence: an explicit deny anywhere wins, otherwise any allow grants, and nothing matched denies.

The same statement format is used by [app permissions](./role-permissions.md#permission-catalogs) and, for
`action` and `condition`, by [rate limit rules](./rate-limiting.md#rules).

## Policy document

```json
{
  "id": "orders",
  "name": "Orders",
  "category": "order",
  "description": "Who may read and change orders",
  "type": "custom",
  "tenantId": "(platform)",
  "condition": { "authenticated": {} },
  "statements": [
    { "name": "read-orders", "action": { "path": { "pattern": "/orders/**", "method": "GET" } } },
    { "name": "no-delete", "effect": "deny", "action": { "path": { "pattern": "/orders/*", "method": "DELETE" } } }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Policy id. Principals list non-global policies by this id. Unique within a set of policy files. Must not start with `history:`, which the Redis repository reserves for the policy history ([Redis keys](../operations/storage.md#redis-keys)). |
| `name` | yes | Display name. |
| `type` | yes | `global`, `system` or `custom`, see below. Case-insensitive. |
| `tenantId` | yes | The owning tenant, e.g. `(platform)` or `(0)`. Metadata only: authorization never compares it with the request's tenant. |
| `category`, `description` | no | Metadata. Default `""`. |
| `condition` | no | Evaluated before the statements; when it does not match, the policy contributes nothing. Default: always matches. |
| `statements` | no | The rules. Default `[]`; a policy without statements never matches. |

| `type` | Evaluated for |
|---|---|
| `global` | Every request. |
| `system` | Principals whose `policies` list the id. Meant for policies managed by platform administrators. |
| `custom` | Principals whose `policies` list the id. Meant for policies users define. |

`system` and `custom` behave identically in authorization; the type is a label for whoever manages the policy.

::: warning `tenantId` does not isolate tenants
A principal of any tenant that lists a policy id gets that policy. To limit a policy to one tenant, add a
condition such as `{ "eq": { "part": "context.tenantId", "value": "t1" } }`.
:::

## Statements

| Field | Required | Default | Meaning |
|---|---|---|---|
| `name` | no | `""` | Reported by explanations, test results, shadow diffs and audit events. Name every statement you test. |
| `effect` | no | `allow` | `allow` or `deny`, case-insensitive. |
| `action` | yes | | Which requests the statement covers ([Actions](#actions)). |
| `condition` | no | always | When the statement applies ([Conditions](#conditions)). |

A statement matches when its action matches and then its condition matches. A matched `allow` yields `ALLOW`, a
matched `deny` yields `EXPLICIT_DENY`.

## Actions

An action selects requests by path and method. The type is the single key of an object; strings and arrays are
shorthands.

| Form | Example | Meaning |
|---|---|---|
| `"*"` | `"action": "*"` | Every request. |
| string | `"action": "/orders/{id}"` | One `path` pattern, any method. |
| array | `"action": ["/orders/**", { "path": { "pattern": "/carts/**" } }]` | `composite`: matches when any element matches. Elements are strings or action objects. |
| `path` | `"action": { "path": { "pattern": "/orders/**", "method": "GET" } }` | Path patterns with methods and options. |
| `all` | `"action": { "all": { "method": "GET" } }` | Every path, optionally limited to some methods. |
| `composite` | `"action": { "composite": ["/a/**", "/b/**"] }` | Same as the array form. |

`path` and `all` accept `method`: one HTTP method or an array, compared case-insensitively. Omitted means any
method. An object with more than one key uses only the first; the others are ignored.

### `path`

```json
{
  "path": {
    "pattern": ["/orders/{orderId}", "/orders/{orderId}/items"],
    "method": ["GET", "HEAD"],
    "options": { "caseSensitive": false, "separator": "/", "decodeAndParseSegments": true }
  }
}
```

| Option | Default | Meaning |
|---|---|---|
| `pattern` | required | A Spring [`PathPattern`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/util/pattern/PathPattern.html) or an array of them: `?`, `*`, `**`, `{var}`, `{var:regex}`, `{*rest}`. An array matches when any pattern matches. A pattern may contain a [template](#templates). |
| `method` | any | See above. |
| `options.caseSensitive` | `true` | Compare path segments case-sensitively. |
| `options.separator` | `/` | One character that splits the path. With `"."`, a "path" such as `order.read` can be matched. |
| `options.decodeAndParseSegments` | `true` | Decode `%xx` and parse `;` path parameters before matching. |

`path` also accepts a bare string or array instead of the object: `{ "path": "/orders/**" }`.

A matching `path` action captures its `{var}`s for the condition of the same statement ([Path variables](#path-variables)).

### `all`

`{ "all": {} }` matches every request; `{ "all": { "method": ["POST", "PUT"] } }` every request with one of the
methods.

### `composite`

An array of actions, written as strings or action objects; it matches when any of them matches.

```json
{ "composite": ["/carts/**", { "path": { "pattern": "/orders/**", "method": "GET" } }] }
```

## Path variables

The variables a `path` action captures (`{orderId}` above) are read as the part `request.path.var.<name>`, only in
the condition of the same statement, permission or rate limit rule:

```json
{
  "name": "own-orders",
  "action": "/users/{userId}/orders/**",
  "condition": { "eq": { "part": "request.path.var.userId", "value": "#{principal.id}" } }
}
```

They are cleared before every statement, permission and rate limit rule matches its action, so a condition never
sees what another statement captured, and a variable its own pattern does not declare reads as `""`. Reading them
anywhere else is a policy error, rejected when the policy, app permission or rule is built with
`PathVariableScopeException`:

| Read in | Why it is rejected |
|---|---|
| a policy's or an app permission's `condition` | it runs before any action captures anything |
| the condition of a statement or permission whose action is not `path`: `all`, `"*"`, a composite with any non-`path` member, a custom action type | that action captures nothing |
| a rate limit rule's `condition` or `key`, when its action is not `path` | same |
| any action's own configuration, e.g. a template mentioning `PATH_VARIABLES` | the action is evaluated before it captures anything |

A configuration reads path variables when any string in it, at any depth, starts with `request.path.var.` or
contains `PATH_VARIABLES` (the context attribute that holds them, which an expression could reach). An expression
that builds that name at run time is not recognized, nor is a custom matcher that reads the variables in code;
they still see nothing outside their own statement, because the variables are cleared.

A composite action captures variables only when every member is a `path` action, so this statement is rejected:

```json
{
  "action": ["/orders/{orderId}", { "all": { "method": "OPTIONS" } }],
  "condition": { "eq": { "part": "request.path.var.orderId", "value": "1" } }
}
```

## Templates

A `path` pattern or an `eq` value that contains `#{...}` is a SpEL template evaluated against the caller's
security context, so one rule can depend on who calls:

| Expression | Value |
|---|---|
| `#{principal.id}` | the principal id |
| `#{principal.attributes['team']}` | a principal attribute |
| `#{tenant.tenantId}` | the tenant id |

```json
{ "name": "own-profile", "action": "/users/#{principal.id}/**" }
```

Templates use SpEL's read-only data binding: they read properties and map entries and cannot call methods, write
or construct anything. Anywhere else, `#{...}` is literal text. A `path` template reads no path variables
([Path variables](#path-variables)).

## Conditions

A condition is an object with a single key, the condition type, whose value is its configuration:

```json
{ "eq": { "part": "context.tenantId", "value": "(platform)" } }
```

Every condition accepts `"negate": true`, which inverts its result. An unknown type fails when the policy is
built. Condition types:

| Group | Types |
|---|---|
| [Context](#context-conditions) | `all`, `authenticated`, `inTenant`, `inRole` |
| [Value](#value-conditions) | `eq`, `in`, `contains`, `startsWith`, `endsWith`, `regular`, `path` |
| [Time](#time-conditions) | `time` |
| [Composing](#composing-conditions) | `bool` |
| [Expressions](#expressions) | `spel`, `ognl` |

### Context conditions

| Type | Configuration | Matches when |
|---|---|---|
| `all` | `{}` | always |
| `authenticated` | `{}` | the principal is not anonymous |
| `inTenant` | `value`: `default`, `user` or `platform` (case-insensitive) | the tenant is the default tenant `(0)`, a user tenant (any other id), or the platform tenant `(platform)` |
| `inRole` | `value`: a role, `role` or `role@space` | the principal holds the role ([Matching roles](#matching-roles)) |

`inTenant` checks the kind of tenant. To check one tenant id, use `eq` on `context.tenantId`.

### Matching roles

A principal's roles are strings: `editor` is a role in the default space, `editor@team-a` a role in space `team-a`.
`inRole` matches them in two ways:

| `value` | Matches a principal holding |
|---|---|
| `role@space`, e.g. `admin@team-a` | exactly `admin@team-a`, whatever space the request names |
| bare `role`, e.g. `suspended` | `suspended` (in every space), or `suspended@<space>` where `<space>` is the request's `CoSec-Space-Id` |

The bare form makes a deny follow a role into spaces: a deny on `inRole: suspended` matches a token carrying
`suspended@team-a` when the request names `team-a`, and a role held without a suffix matches in every space, so
naming another space cannot step out of such a deny.

Neither form limits an **allow** to the request's space. An allow on `admin@team-a` also matches when the request
names `team-b` or no space. To grant only inside a space, add a `request.spaceId` condition:

```json
{
  "name": "team-a-admins",
  "action": "/team-a/**",
  "condition": {
    "bool": {
      "and": [
        { "inRole": { "value": "admin@team-a" } },
        { "eq": { "part": "request.spaceId", "value": "team-a" } }
      ]
    }
  }
}
```

Role grants in app permissions need no such condition; they are scoped to the request's space
([Role grant scope](./role-permissions.md#role-grant-scope)). Expressions over the principal's roles see the raw
strings, `suspended@team-a` included.

### Value conditions

These read one [part](#parts) of the request or security context and compare it as a string.

| Type | Configuration | Matches when the part … |
|---|---|---|
| `eq` | `part`, `value` ([template](#templates) allowed), `ignoreCase` | equals `value` |
| `in` | `part`, `value` (array of strings) | equals one of `value` (case-sensitive) |
| `contains` | `part`, `value`, `ignoreCase` | contains `value` |
| `startsWith` | `part`, `value`, `ignoreCase` | starts with `value` |
| `endsWith` | `part`, `value`, `ignoreCase` | ends with `value` |
| `regular` | `part`, `pattern`, `timeout` (ms, default `1000`) | matches the regular expression as a whole, ignoring case |
| `path` | `part`, `pattern`, `options` | matches the path pattern; `options` as for the [`path` action](#path) |

`ignoreCase` defaults to `false`. `regular` runs under its `timeout` as a guard against catastrophic backtracking;
a match that exceeds it is never treated as a match and denies the request: `401` for an anonymous caller, `403`
for an authenticated one ([Responses](../integration/web.md#responses-and-status-codes)).

```json
{ "in": { "part": "request.remoteIp", "value": ["10.0.0.1", "10.0.0.2"] } }
```

```json
{ "regular": { "part": "request.header.User-Agent", "pattern": ".*curl.*" } }
```

```json
{ "path": { "part": "request.attributes.target", "pattern": "order.*", "options": { "separator": "." } } }
```

### Parts

The complete list, as `DefaultPartExtractor` reads them. A part that is absent reads as `""`.

| Part | Value |
|---|---|
| `request.path` | request path |
| `request.method` | HTTP method |
| `request.remoteIp` | client IP ([behind proxies](../integration/web.md#client-ip-behind-proxies)) |
| `request.appId`, `request.spaceId`, `request.deviceId` | the `CoSec-App-Id`, `CoSec-Space-Id`, `CoSec-Device-Id` header, or query parameter ([App and space](../integration/web.md#app-and-space)) |
| `request.origin`, `request.origin.host` | `Origin` header, and its host |
| `request.referer`, `request.referer.host` | `Referer` header, and its host |
| `request.header.<name>` | any request header |
| `request.attributes.<name>` | a [request attribute](../integration/web.md#request-attributes), such as `ipRegion` |
| `request.path.var.<name>` | a variable captured by the same statement's `path` action ([Path variables](#path-variables)) |
| `resource.<name>` | an attribute of the target object, set by a [check in code](../integration/checks.md#object-level-rules); `""` in the request filter |
| `context.tenantId` | tenant id |
| `context.principal.id` | principal id |
| `context.principal.attributes.<name>` | a principal attribute, as a string |

A prefixed part needs a name after the prefix. A value condition whose part is outside this list fails when the
policy loads, with `Condition [<type>] part [<part>] is not a supported part`. A copy stored by a version before 7.0
fails every read of that policy with `500` until it is fixed and saved again.

Headers, `request.remoteIp` and request attributes are only as trustworthy as their source. Do not base an allow on
a header the client can set unless a trusted proxy overwrites it.

### Time conditions

`time` matches inside a validity period and, optionally, a recurring daily window. It reads the server clock,
never the request.

| Configuration | Meaning |
|---|---|
| `notBefore`, `notAfter` | The period as ISO date-times, `notBefore` inclusive, `notAfter` exclusive. A date-time without an offset is read in `zone`. |
| `daysOfWeek` | Days the condition applies, e.g. `["MON", "TUE"]`; full names work too. |
| `from`, `to` | A daily window `[from, to)` as `HH:mm` or `HH:mm:ss`. When `from` is after `to` the window crosses midnight, and its early-morning part belongs to the day it started on, so `daysOfWeek` checks that day. |
| `zone` | Time zone of the window and of offset-less date-times. Default `UTC`. |

The configuration is checked when the policy is built: at least one of `notBefore`, `notAfter`, `daysOfWeek` or
`from`/`to`; `from` and `to` together and not equal; `notBefore` before `notAfter`; `daysOfWeek` not empty.

```json
{
  "name": "temporary-support-access",
  "action": "/orders/**",
  "condition": {
    "bool": {
      "and": [
        { "inRole": { "value": "support" } },
        { "time": { "notAfter": "2026-12-31T00:00:00", "zone": "Asia/Shanghai" } }
      ]
    }
  }
}
```

```json
{
  "name": "no-writes-outside-business-hours",
  "effect": "deny",
  "action": { "all": { "method": ["POST", "PUT", "PATCH", "DELETE"] } },
  "condition": {
    "time": { "daysOfWeek": ["MON", "TUE", "WED", "THU", "FRI"], "from": "09:00", "to": "18:00", "zone": "Europe/Berlin", "negate": true }
  }
}
```

## Composing conditions

`bool` combines conditions:

| Key | Meaning |
|---|---|
| `and` | every condition must match |
| `or` | when present and not empty, at least one must match as well |
| `negate` | inverts the combined result |

`{ "bool": {} }` always matches. `bool` nests to any depth.

```json
{
  "bool": {
    "and": [{ "authenticated": {} }],
    "or": [
      { "inRole": { "value": "admin" } },
      { "eq": { "part": "context.principal.attributes.team", "value": "ops" } }
    ]
  }
}
```

## Expressions

| Type | Configuration | Evaluated |
|---|---|---|
| `spel` | `expression` | Spring Expression Language with read-only data binding. The root has `request` and `context`: `context.principal.attributes['tier'] == 'gold'`. Properties and map entries only; method calls fail. |
| `ognl` | `expression` | OGNL in a sandbox. The root object is the request (`path`, `method`, `appId`, …); `#request` and `#context` name the request and the security context: `#context.principal.roles.contains('admin')`. |

```json
{ "spel": { "expression": "context.principal.attributes['tier'] == 'gold'" } }
```

```json
{ "ognl": { "expression": "#context.principal.roles.contains('admin') && method == 'GET'" } }
```

Prefer the declarative conditions. Expressions are harder to review and test, and their risks differ:

- **SpEL** reads properties and map entries only: no method calls, no writes, no type references or constructors.
  It is the choice when policy authors are not fully trusted.
- **OGNL** may call public methods, so it is more expressive and riskier. The sandbox denies non-public members and
  fields, setters and mutating collection methods (`put`, `add`, `remove`, `clear`, …), `getClass` and
  `getDelegate`, members of `Runtime`, `Process`, `System`, `Thread`, `Class`, `ClassLoader` and of the `java.io`,
  `java.nio`, `java.lang.reflect` and similar packages, and every class reference (`new`, `@Class@member`). The
  read-only check is by method name and signature and is best-effort; treat OGNL policies as code.
- Neither is checked for [parts](#parts) or [path variables](#path-variables) beyond the `PATH_VARIABLES` text
  check, and an expression error fails the request with `500`.

## Loading policy files

Policies reach authorization through the `PolicyRepository`. Local files are one way to fill it; enabling them and
initializing the repository are described in [Storage](../operations/storage.md#local-policy-files). Wherever
policies are read from files (local policies, [shadow candidates](./testing.md#trying-changes-in-shadow-mode),
[`PolicyTestKit`](./testing.md#policy-test-cases), [`AccessAnalyzer`](./testing.md#access-reviews)), loading
is strict, because a file that is silently skipped could drop a deny:

- A file that cannot be read or built fails: invalid JSON, a missing required field, an unknown action or
  condition type, an invalid matcher configuration (for example a `time` condition without any bound, or a value
  condition with an unsupported [part](#parts)), or a [path variable](#path-variables) read outside its scope. The
  error names the file.
- Two files that declare the same policy id fail, naming both files.
- A configured location that does not exist or matches no file fails. Only the default location,
  `classpath:cosec-policy/*-policy.json`, may match nothing.
- A file matched by two locations is read once.

In the application these failures stop startup. Errors that depend on the request are not detected here;
[validate policies](./testing.md#validating-policies) for them.

## Generating policies from OpenAPI

With `cosec-openapi` and Spring Boot Actuator, `GET /actuator/cosecPolicyGenerator` turns the application's OpenAPI
description into a policy draft: one `allow` statement per operation, with the operation's path and method as a
`path` action and its summary (or operation id) as the name. The draft has the placeholder id `PolicyId`, type
`system` and tenant `(0)`. Set the id and metadata, narrow or delete statements, and review it before use. App
permission catalogs are generated the same way ([Generating a catalog from OpenAPI](./role-permissions.md#generating-a-catalog-from-openapi)).

## JSON Schema

[`schema/`](https://github.com/Ahoo-Wang/CoSec/tree/main/schema) holds JSON Schemas for editor completion and
pre-commit checks:

| File | Validates |
|---|---|
| `cosec-policy.schema.json` | a policy file |
| `cosec-app-permission.schema.json` | an app permission file |
| `rate-limit-rule.schema.json` | a rate limit rules file |
| `action.schema.json`, `condition.schema.json`, `definitions.schema.json` | shared parts |

Reference one from a file with `$schema`, which the parser ignores:

```json
{
  "$schema": "https://raw.githubusercontent.com/Ahoo-Wang/CoSec/main/schema/cosec-policy.schema.json",
  "id": "orders",
  "name": "Orders",
  "type": "custom",
  "tenantId": "(platform)",
  "statements": [{ "action": "/orders/**" }]
}
```

The schemas describe the shape only. They do not check parts, path variable scope, duplicate ids or matcher
semantics, so also load your policies with the real parser ([Testing](./testing.md#validating-policies)).

**Next:** grant permissions by role instead of per principal, in [Role permissions](./role-permissions.md).

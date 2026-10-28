---
title: "Policy Language"
description: "Every field of a CoSec document: platform, app and tenant documents, policies, statements, actions, resources, conditions, bindings, catalogs and rate limit rules."
---

# Policy Language

For policy authors, reviewers and tool builders who need the exact meaning of a field. This page is the reference
of the document format (`schema: 2`). How the fields combine into a decision is in the
[security model](../guide/security-model.md); how to change documents safely is in [Testing policies](./testing.md).

All authorization data lives in three kinds of documents. A document is read strictly and compiled whole: an unknown
field, a repeated key, a value of the wrong JSON type, a pattern or condition that does not compile, or a dangling
reference makes the whole document fail. A document that fails is never installed, and every request that needs it
fails closed: `503` unless a DENY matches ([fail closed](../guide/security-model.md)).

## Documents

| Document | Key | One per | Holds |
|---|---|---|---|
| Platform | `platform` | deployment | Policies shared by every tenant, bindings for anyone (`*`) and for named principals, break-glass bindings, rate limit rules, and what only the platform sets: `limits`, `attributes`, `boundaries`. |
| App | `app:<appId>` | app | The app's [catalog](#catalogs), its permission policies `perm:<appId>/<permission>`, role bindings, rate limit rules. |
| Tenant | `tenant:<tenantId>` | tenant | The tenant's own policies, its bindings (anyone, its principals, its roles), its `boundary`, rate limit rules. |

The key is how the [control plane](./testing.md) names a document. Every request reads the platform document and the
document of its tenant (anonymous requests: tenant `(0)`). It reads the app document of its app only when the
principal holds at least one role in the request's space; a request the blacklist blocks reads only the platform
document. A document nobody published reads as empty. Which candidates apply, and in which order, is in the
[security model](../guide/security-model.md).

A tenant document:

```json
{
  "schema": 2,
  "revision": 3,
  "publishedAt": "2026-11-01T08:00:00Z",
  "policies": [
    {
      "id": "order-reader",
      "statements": [
        {
          "name": "read-orders",
          "effect": "allow",
          "action": "order:read",
          "resource": "order/**"
        }
      ]
    }
  ],
  "bindings": [
    {
      "id": "support-reads-orders",
      "subject": { "role": "support" },
      "app": "order",
      "space": "",
      "policy": "order-reader"
    }
  ]
}
```

### Header

| Field | Type | Required | Meaning |
|---|---|---|---|
| `schema` | integer | yes | The format version: always `2`. Any other value fails the document. |
| `revision` | integer | yes | Positive. Each publish writes the next one. |
| `publishedAt` | string | yes | An ISO-8601 instant with `Z` or an offset, such as `2026-11-01T08:00:00Z`. |
| `provenance` | object | no | How the revision was published ([Provenance](#provenance)). Written by the control plane and `cosec-migrate`. |

When you submit a whole document to the control plane (`:apply`), the header must be present and valid, but the
control plane writes its own `revision`, `publishedAt` and `provenance` when it publishes.

### Sections by document

| Field | Platform | App | Tenant |
|---|---|---|---|
| `policies`, `bindings`, `rateLimits` | yes | yes | yes |
| `catalog` | | yes | |
| `limits`, `attributes` | yes | | |
| `boundaries` | yes | | |
| `boundary` | | | yes |

A field a document kind does not take fails that document.

## Policies

A policy is a named list of statements. It does nothing until a [binding](#bindings) or an
[attachment](#attachments) makes it apply to someone.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | required | Unique in its document. See the rules below. |
| `attachable` | boolean | `false` | Whether a principal may [attach](#attachments) it. |
| `boundary` | boolean | `false` | A platform [boundary](#boundaries) policy. |
| `condition` | condition | none | Guards every statement of the policy. It cannot read [path variables](#path-variables). It is evaluated at most once per request, and only after one of its statements matched the action and resource. |
| `statements` | array | required | The [statements](#statements). |

Policy ids:

- `perm:<appId>/<permission>` names an app's permission policy. Only that app's document defines it, and it cannot be
  attachable ([Role permissions](./role-permissions.md)).
- `cosec:blacklist`, `cosec:root`, `cosec:platform-admin`, `cosec:tenant-admin` and `cosec:tenant-viewer` are
  built-in policies; no document defines them. The control plane also refuses new ids that start with `cosec:`,
  `g:` or `r:`, which are reserved for built-in policies and `cosec-migrate`.
- A tenant policy must not reuse the id of a platform policy. The control plane refuses it. On the data plane, a
  binding or attachment that names such an id makes the request Indeterminate (`503`) unless a DENY matches or both
  policies have the same content.

## Statements

A statement matches a request when one of its action patterns matches the action, one of its resource patterns
matches the resource, its condition is true, and its policy's condition is true.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `name` | string | `""` | Reported in decisions, traces and `explain`. |
| `effect` | `"allow"` or `"deny"` | required | |
| `action` | [action pattern](#actions), or a non-empty array of them | required | Write `"*"` for every action. |
| `resource` | [resource pattern](#resources) or pattern object, or a non-empty array of them | any resource | |
| `condition` | [condition](#conditions) | always true | |
| `onMissing` | `"indeterminate"` or `"nomatch"` | `"indeterminate"` | What an external attribute that cannot be fetched means ([External attributes](#external-attributes-and-onmissing)). Only for a condition that reads one. |
| `onMissingConfirmed` | object | none | Who confirmed a DENY with `onMissing: "nomatch"`, and in which change: `{"by": {"tenant", "principal"}, "change"}`. Only the control plane writes it. |

A DENY cannot name a bare resource type such as `order`: it would cover only the resource with an empty id. Write
`order/**` for every order. Such a statement does not compile.

## Actions

| Pattern | Matches |
|---|---|
| `service:verb` | That action only. |
| `service:*` | Every verb of the service. |
| `*` | Every action. |

There is no other wildcard. Service and verb compare exactly, case included (`http:get` is not `http:GET`). Neither
may be empty or contain `:`, `*`, whitespace or control characters.

Where a request's action comes from:

- An HTTP request whose route is in the service's [route map](#route-maps) has that route's `service:verb` action and
  typed resource.
- Any other HTTP request has the action `http:<METHOD>` (the method as received, such as `http:GET`) on the resource
  of type `http` whose id is its path. Statements on `service:verb` never match it, and statements on `http:*` never
  match a mapped request.
- A check in code names the action and resource itself ([Checks in code](../integration/checks.md)).

### Route maps

A route map (`cosec-routes.json`, loaded from `cosec.authorization.routes.location`,
[Configuration](../operations/configuration.md)) gives a service's HTTP routes their actions and typed resources:

```json
{
  "service": "order",
  "routes": [
    { "method": "GET", "path": "/orders/{orderId}", "action": "order:read", "resource": "order/{orderId}" },
    { "method": "POST", "path": "/orders/{orderId}/cancel", "action": "order:cancel", "resource": "order/{orderId}" }
  ]
}
```

`path` is an [HTTP path pattern](#http-paths). `resource` is a typed resource whose segments are literals or variables
of `path`. A route map loads whole or not at all: a malformed route, or two routes of one method that the same request
could match, fails startup. A captured value that holds `/` (a decoded `%2F`) would change the resource id's segments,
so such a request is refused with `400`. A gateway loads no route map: its requests stay in the `http` namespace.
`cosecServiceGenerator/routes` generates a route map from the service's OpenAPI document
([Role permissions](./role-permissions.md#generating-a-catalog-from-openapi)).

## Resources

A resource is a type and an id: `order` and `42` for `order/42`, `http` and `/orders/42` for an HTTP path.

### Typed resources

`type`, or `type/segment/...`. Each segment is one of:

| Segment | Matches |
|---|---|
| a literal | that text exactly, case included |
| `*` | one non-empty segment |
| `{name}` | one non-empty segment, captured as a [path variable](#path-variables) |
| `**`, last only | any number of further segments, none included |

| Pattern | `order` (empty id) | `order/42` | `order/42/items` |
|---|---|---|---|
| `order` | yes | | |
| `order/*` | | yes | |
| `order/{orderId}` | | yes | |
| `order/**` | yes | yes | yes |
| `order/*/items` | | | yes |

Ids are split on `/` and compared as written: never decoded, never trimmed. The type cannot be `http`, and cannot
contain `:`; no literal may contain `*`, `?`, `{`, `}`, whitespace or control characters.

### HTTP paths

`http:path:<pattern>` matches a resource of type `http` whose id is the request path, as the pipeline normalized it
([Web integration](../integration/web.md)). It matches what Spring's `PathPattern` matches, on this subset:

| Element | Matches |
|---|---|
| a literal segment | that segment |
| `?` | one character |
| `*` within a segment | zero or more characters, as in `/files/*.pdf` |
| `*` as a segment | one segment |
| `{name}` | one segment, captured |
| `**`, or `{*name}` | any number of segments, captured by `{*name}` as the rest of the path; only first or last, at most one per pattern. `/admin/**` also matches `/admin`. |

These do not compile: `{name:regex}`, an empty pattern, a segment that can match an empty value other than `*` alone
(such as `{a}{b}` or `*{a}`), and `{}` or `{*}`.

A resource may be a pattern object with options, for `http:path:` patterns only:

```json
{ "pattern": "http:path:/Reports/{id}", "caseSensitive": false, "decodeAndParseSegments": true }
```

| Option | Default | Meaning |
|---|---|---|
| `caseSensitive` | `true` | `false` compares literals ignoring ASCII case. |
| `decodeAndParseSegments` | `true` | `true` matches each segment by its percent-decoded value without `;` parameters; `false` by its text as received, except that `%2F` reads as `/`. |

## Path variables

`{name}` in a typed or HTTP pattern, and `{*name}` in an HTTP pattern, capture a variable. Only the statement whose
pattern captured it can read it, as `resource.var.<name>`, in its own condition or a rate limit rule's `key`:

```json
{
  "name": "own-profile",
  "effect": "allow",
  "action": "http:*",
  "resource": "http:path:/users/{userId}/**",
  "condition": { "eq": ["resource.var.userId", { "attr": "subject.id" }] }
}
```

- Every resource pattern of the statement must capture the variable it reads, or the statement does not compile: a
  variable some pattern does not capture would read as absent, and a DENY written against it would silently miss.
  A policy condition cannot read any variable.
- A name is a Java identifier that may also contain `-`, and is captured once per pattern.
- A captured value is the segment's decoded value (`{*name}`: the rest of the path). A value is only ever compared:
  it never becomes part of a pattern, so a principal id of `**` cannot widen anything.

## Conditions

A condition is a JSON object with exactly one operator; `ignoreCase` may stand beside `eq` and `prefix`. Every
attribute path and every operand type is checked when the document compiles.

```json
{ "and": [
  { "eq": ["subject.authenticated", true] },
  { "in": ["scope.space", ["team-a", "team-b"]] },
  { "not": { "cidr": ["environment.ip", ["10.0.0.0/8"]] } },
  { "eq": ["resource.var.ownerId", { "attr": "subject.id" }] }
] }
```

### Operators

| Operator | Form | True when | Attribute types |
|---|---|---|---|
| `eq` | `[attribute, literal]` | the value equals the literal. With `"ignoreCase": true` (String only), ignoring ASCII case. An Ip attribute compares with an address. | String, Bool, Ip |
| `eq` | `[attribute, {"attr": attribute}]` | both values are present and equal; both attributes have the same type | String, Bool, Ip |
| `in` | `[attribute, [literals]]` | the value is one of the literals: 1 to `maxInValues` of them | String, Bool, Ip |
| `in` | `[literal, attribute]` | the set holds the string literal | Set |
| `prefix` | `[attribute, "text"]` | the value starts with the text; `ignoreCase` allowed. On a Labels attribute, whole labels: `com.example` matches `example.com` and `api.example.com`, not `badexample.com`. | String, Labels |
| `cidr` | `[attribute, ["block", ...]]` | the address is in one of the IPv4 or IPv6 blocks: 1 to `maxCidrBlocks` | Ip |
| `time` | `{...}` | the request time is in the window ([Time conditions](#time-conditions)) | |
| `exists` | `"attribute"` | the attribute is present (for Labels, its host is) | any |
| `and` | `[conditions]` | all are true; at least one, left to right, stops at the first false | |
| `or` | `[conditions]` | any is true; at least one, left to right, stops at the first true | |
| `not` | `condition` | the condition is false | |

Literals are JSON strings or booleans, never numbers; a string is never read as a boolean. There is no `true` or
`false` condition: leave `condition` out instead.

### Attributes

| Attribute | Type | Value |
|---|---|---|
| `subject.id` | String | The principal id; absent for an anonymous principal. |
| `subject.tenant` | String | The request's tenant: the principal's, `(0)` when anonymous. |
| `subject.authenticated` | Bool | Whether a credential was accepted. |
| `subject.roles` | Set | The principal's role ids, in any space. |
| `subject.scopedRoles` | Set | Its roles as claimed: `role` in the default space, `role@space` in another. |
| `subject.attributes.<name>` | String | The token's `attributes` claim entry, as text ([Authentication](../integration/authentication.md)). |
| `scope.tenant` | String | Same as `subject.tenant`. |
| `scope.app` | String | The request's app; absent when it has none. |
| `scope.space` | String | The request's space; `""` for the default space. |
| `scope.roles` | Set | The principal's roles in the request's space. |
| `action.service`, `action.verb` | String | The two parts of the action. |
| `resource.type`, `resource.id` | String | The two parts of the resource. |
| `resource.attributes.<name>` | String | Attributes of a resource that code passes to a check. The request pipeline sets none. |
| `resource.var.<name>` | String | A [path variable](#path-variables). |
| `environment.ip` | Ip | The client address ([Web integration](../integration/web.md)). |
| `environment.time` | Instant | The request time; only `time` reads it. |
| `environment.device` | String | The `CoSec-Device-Id` parameter; absent when empty. |
| `environment.blocked` | Bool | Whether the blacklist blocks the request. |
| `http.header.<name>` | String | The request header `<name>`; absent when the request has none. See [Headers](#headers). |
| `http.origin`, `http.referer` | String | The `Origin` and `Referer` headers, read as `http.header.*` is. |
| `http.origin.host`, `http.referer.host` | String | The host of the `Origin` and `Referer` URLs, in lower case; absent when the value is not a URL with a host. |
| `http.origin.host.labels`, `http.referer.host.labels` | Labels | That host's labels, reversed (`api.example.com` is `com.example.api`), in lower case; for `prefix`. |
| `request.attributes.<name>` | String | What the host knows about the request besides HTTP. The gateway sets `request.attributes.cosec.audit.routeId`, the id of the route the request came through, and `request.attributes.cosec.identity.forward` (`false`) on a route whose metadata opts out of identity propagation (`cosec-identity: false`); WebFlux and WebMvc set none. |
| `ext.<provider>.<name>` | declared | An [external attribute](#external-attributes-and-onmissing). |

A path outside this table does not compile.

### Headers

Header names are case-insensitive: `http.header.X-Team` is `http.header.x-team`.

No attribute reference can read a header that carries a credential: `http.header.authorization`,
`proxy-authorization`, `cookie`, `set-cookie`, `x-api-key` and `cosec-identity`, in any case. That covers a condition
and a rate limit rule's `key` alike. A document with such a reference does not compile, through the control plane and
`cosec-migrate` alike. A DENY with `prefix` on `authorization` would let a document's author learn another principal's
token one character at a time, and a key on it would count requests per token. `cosec-identity` is what a gateway forwards to the services behind it; at the
gateway it is only the client's claim. The list is fixed
([`ConditionChecker.CREDENTIAL_HEADERS`](https://github.com/Ahoo-Wang/CoSec/blob/main/model/src/main/kotlin/me/ahoo/cosec/model/condition/ConditionChecker.kt));
a host that reads API keys from another header is not covered by it.

A header sent more than once reads as its value when every copy is the same. When the copies differ, the header has no
single value, and a statement that reads it errs: a DENY on it makes the request Indeterminate (`503`) unless another
DENY matches, and an ALLOW on it does not match. The first or last copy never decides.

### Matching roles

Roles grant permissions through [role bindings](./role-permissions.md), not conditions. When a condition needs a
role, test the set:

```json
{ "in": ["auditor", "scope.roles"] }
```

`scope.roles` holds the roles of the request's space only, which is usually what you mean; `subject.roles` holds a
role whatever space it is in; `subject.scopedRoles` holds `role@space` strings. An anonymous principal has no roles.

### Absent values

An attribute is absent when the request has no value for it, such as `subject.id` of an anonymous principal or
`scope.app` of a request without an app.

- `eq`, `in`, `prefix`, `cidr` and `time` are false for an absent value; `exists` is false.
- Absent is equal to nothing: not to `""`, and not to another absent value.
- `not` is plain negation, so `not` over a comparison with an absent value is true. A DENY written as
  `{"not": {"eq": [...]}}` also matches requests where the attribute is absent.

### Time conditions

`time` reads `environment.time`.

| Field | Meaning |
|---|---|
| `notBefore` | ISO date-time; the condition is false before it. Without an offset, read in `zone`. |
| `notAfter` | ISO date-time; false from it on. Must be after `notBefore`. |
| `zone` | Time zone id: `UTC` (the default), an offset such as `+08:00` or `UTC+8`, or a region such as `Asia/Shanghai`. |
| `daysOfWeek` | Non-empty array of `MON` … `SUN`. |
| `from`, `to` | Time of day `HH:mm`, `HH:mm:ss` or `HH:mm:ss.fff`; both or neither, and not equal. The window is `[from, to)`. When `from` is after `to` it crosses midnight, and its early-morning part belongs to the day it started on. |

At least one of `notBefore`, `notAfter`, `daysOfWeek` or `from`/`to` is required.

```json
{ "time": { "zone": "Asia/Shanghai", "daysOfWeek": ["MON", "TUE", "WED", "THU", "FRI"], "from": "09:00", "to": "18:00" } }
```

### External attributes and onMissing

`ext.<provider>.<name>` attributes come from an `AttributeProvider` ([Extending](../architecture/extending.md)),
fetched only for requests whose candidate statements read them. Their type is declared: `string`, `bool`,
`stringSet` or `ip`. A path no provider and no platform [`attributes`](#limits-and-attributes) entry declares does not
compile. Every instance compiles against both its own providers and the platform's `attributes`, and so does the
control plane when it checks a change; the providers must therefore be the same on every host
([Testing policies](./testing.md#trying-changes-in-shadow-mode)).

A provider may return no value (absent, as above), or fail, time out or not be asked: then the attribute is
*missing*, and the statement's `onMissing` decides:

| `onMissing` | DENY | ALLOW |
|---|---|---|
| `indeterminate` (default) | The decision is Indeterminate (`503`) unless another DENY matches. | Skipped. If nothing else permits, the decision is Indeterminate (`503`), not a deny. |
| `nomatch` | The DENY does not match: it lapses while the attribute is missing. Compiles only with `onMissingConfirmed`, which the control plane writes when the change's author confirms it ([Testing policies](./testing.md#changes)). | Skipped, and the request is decided without it. |

A missing attribute never turns a Deny or Indeterminate into a Permit, except through a confirmed `nomatch` DENY.

### Limits

Every condition is bounded when it compiles. The platform document sets the bounds for every document
([`limits`](#limits-and-attributes)); absent ones are the defaults.

| Limit | Default | Bounds |
|---|---|---|
| `maxNodes` | 64 | Nodes of one condition. |
| `maxDepth` | 8 | Nesting depth of one condition. |
| `maxInValues` | 1024 | Literals of one `in`, and days of one `time`. |
| `maxCidrBlocks` | 256 | Blocks of one `cidr`. |
| `maxLiteralBytes` | 1024 | UTF-8 bytes of one literal or attribute path. |
| `maxCost` | 256 | The sum of node weights of one condition: `and`, `or`, `not`, `exists` 1; `eq`, `prefix` 2; `in`, `cidr` 4; `time` 8. |

## Bindings

A binding says: *subject* is subject to *policy* in *app* and *space*.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | required | Unique in its document. The control plane refuses new ids that start with `cosec:`, `g:` or `r:`. |
| `subject` | `"*"` or object | required | Who: see below. |
| `app` | string | required | An app id, or `*` for any app. Never empty. |
| `space` | string | required | A space id, `""` for the default space, or `*` for any space. |
| `policy` | string | required | The policy id. |
| `expiresAt` | string | none | An ISO-8601 instant. From then on the binding does not apply. |
| `breakGlass` | boolean | `false` | A break-glass binding ([security model](../guide/security-model.md)). |

`app` and `space` compare exactly, case included; `*` means any and only that.

| Subject | Platform | App | Tenant |
|---|---|---|---|
| `"*"`: anyone, anonymous principals included | yes | | yes |
| `{"principal": "<id>", "tenant": "<tenant>"}` | yes, `tenant` required | | yes, `tenant` may be left out (this tenant) |
| `{"role": "<id>"}`, optional `"tenant"` | | yes; no `tenant`: any tenant | yes; no `tenant`: this tenant |

A binding that names another tenant in a tenant document, an app document binding scoped to another app, and a role
binding in the platform document do not compile: they could never apply, and a DENY would be lost silently.

What a binding may name in `policy`:

- a policy of its own document;
- in an app or tenant document, a platform policy. It is looked up per request: if the platform no longer has it,
  requests it applies to are Indeterminate (`503`) unless a DENY matches. The control plane refuses to publish a
  reference that does not resolve, and refuses to remove a platform policy another document refers to;
- an app's permission policy: only in that app's document, or in a tenant document with `app` set to that app
  ([Role permissions](./role-permissions.md#tenant-role-bindings));
- `cosec:root`, only from a break-glass binding; `cosec:platform-admin`, `cosec:tenant-admin` and
  `cosec:tenant-viewer`, which authorize the control plane.

A boundary policy and `cosec:blacklist` are never bound. A break-glass binding is only in the platform document, binds
a principal of the `(platform)` tenant, and has `expiresAt`. A document binds at most 1,000 principals: grant users
through roles in their token or a `MembershipProvider` instead.

## Attachments

A principal's memberships can list policies to attach directly: the token's `policies` claim, or a
`MembershipProvider` ([Authentication](../integration/authentication.md)). An attachment applies in every app and
space. The policy must be `attachable: true` and defined in the principal's tenant document or in the platform
document. An attachment that names any other policy is missing, and the request is Indeterminate (`503`) unless a DENY
matches. Anonymous principals have no attachments.

## Catalogs

An app document's `catalog` lists the permissions the app offers, for consoles and generators. It takes no part in
evaluation.

| Field | Type | Meaning |
|---|---|---|
| `groups` | array, required | Groups of permissions. |
| `groups[].name` | string, required | |
| `groups[].description` | string | |
| `groups[].spaced` | boolean, default `false` | Whether the group's permissions are meant to be granted per space; informational. |
| `groups[].permissions` | array, required | |
| `groups[].permissions[].id` | string, required | The permission id; its policy is `perm:<appId>/<id>`. |
| `groups[].permissions[].name`, `.description` | string | |

Nothing checks that a catalog entry has a policy, or the reverse. See [Role permissions](./role-permissions.md).

## Rate limit rules

`rateLimits` is an ordered array of rules. A permitted request spends one permit of every rule it matches
([Rate limiting](./rate-limiting.md)).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | required | Letters, digits, `.`, `_` and `-`. Unique in its document. |
| `action` | action pattern or non-empty array | required | Which requests count. |
| `resource` | resource pattern or non-empty array | any resource | |
| `condition` | condition | always | |
| `onMissing` | `"indeterminate"` or `"nomatch"` | `"indeterminate"` | For an external attribute that cannot be fetched: `indeterminate` leaves it to `strictFailure`; `nomatch` skips the rule. `nomatch` compiles only in the platform document: rate limit rules have no confirmation, unlike a [DENY](#external-attributes-and-onmissing). |
| `key` | attribute path | one shared bucket | A String or Ip attribute whose value names the bucket. `resource.var.<name>` must be captured by every resource pattern. |
| `permitsPerSecond` | number | required | Greater than `0`. |
| `windowSeconds` | integer | `1` | At least `1`. |
| `strictFailure` | boolean | `false` | Whether a limiter that cannot count refuses the request (`503`). |

A rule matches as a DENY statement does: action, resource and condition, with its own path variables; a bare
resource type does not compile.

## Platform sections

### Limits and attributes

`limits` sets the [condition limits](#limits) every document compiles against. `attributes` declares the external
attributes documents may read:

```json
{ "path": "ext.geo.country", "type": "string" }
```

`path` is `ext.<provider>.<name>`; `type` is `string`, `bool`, `stringSet` or `ip`; each path once. When a platform
revision changes either section, every instance compiles the app and tenant documents again; a document that no longer
compiles fails closed. The control plane warns about this when it checks such a change.

### Boundaries

A boundary caps what a tenant can be permitted. A policy with `"boundary": true` is a boundary: only in the platform
document, only ALLOW statements, at most 64 of them, never attachable, never bound. `boundaries.default` names the
boundary that holds every tenant; a tenant document's `boundary` names the platform boundary that holds that tenant
instead. The `(platform)` tenant has none. A permit then stands only if a statement of the tenant's boundary also
matches; otherwise it becomes a Deny, or Indeterminate when a boundary statement failed or the named boundary is
missing.

A platform document:

```json
{
  "schema": 2,
  "revision": 12,
  "publishedAt": "2026-11-01T08:00:00Z",
  "limits": { "maxCost": 128 },
  "attributes": [{ "path": "ext.geo.country", "type": "string" }],
  "boundaries": { "default": "boundary-standard" },
  "policies": [
    {
      "id": "deny-admin-outside-office",
      "statements": [
        {
          "effect": "deny",
          "action": "*",
          "resource": "http:path:/admin/**",
          "condition": { "not": { "cidr": ["environment.ip", ["10.0.0.0/8"]] } }
        }
      ]
    },
    {
      "id": "public-health",
      "statements": [{ "effect": "allow", "action": "http:GET", "resource": "http:path:/actuator/health" }]
    },
    {
      "id": "boundary-standard",
      "boundary": true,
      "statements": [{ "effect": "allow", "action": ["http:*", "order:*"] }]
    }
  ],
  "bindings": [
    { "id": "deny-admin", "subject": "*", "app": "*", "space": "*", "policy": "deny-admin-outside-office" },
    { "id": "health", "subject": "*", "app": "*", "space": "*", "policy": "public-health" },
    {
      "id": "ops-platform-admin",
      "subject": { "principal": "ops-1", "tenant": "(platform)" },
      "app": "*",
      "space": "*",
      "policy": "cosec:platform-admin"
    }
  ]
}
```

## Provenance

The control plane and `cosec-migrate` record how each revision was published. It decides nothing.

| Field | Meaning |
|---|---|
| `parent` | `{"revision", "digest"}` of the head this revision replaced (`digest`: 40 lowercase hex digits); absent for the first revision. |
| `change` | The change that published it. |
| `kind` | `PUBLISH`, `ROLLBACK`, `BREAK_GLASS`, `EXPIRE`, `MIGRATE` or `BOOTSTRAP`. |
| `publishedBy` | `{"tenant", "principal"}`. |
| `approvedBy` | Approvers, when the change needed approval. |
| `shadow` | `{"window", "skipped"}`: the shadow window the change went through, or that it was skipped. |

## Publishing documents

Documents are stored in Redis and written only through the control plane (the starter's `controlSupport`) or
`cosec-migrate publish` and `bootstrap`; see [Storage](../operations/storage.md). The control plane checks and compiles
every change before it publishes it, and can try it on live traffic first: see [Testing policies](./testing.md).

## JSON Schema

[`schema/`](https://github.com/Ahoo-Wang/CoSec/tree/main/schema) holds JSON Schemas for editor completion and checks
in CI:

| File | Validates |
|---|---|
| `cosec-snapshot-document.schema.json` | a platform, app or tenant document |
| `cosec-binding.schema.json` | one binding |
| `cosec-catalog.schema.json` | an app's catalog |
| `definitions.schema.json` | shared definitions |
| `problem.schema.json` | an error response body ([Web integration](../integration/web.md)) |

A document is closed, so do not add a `$schema` field to it: map the schema to your files in the editor instead, for
example in VS Code's `settings.json`:

```json
{
  "json.schemas": [
    {
      "fileMatch": ["policies/*.json"],
      "url": "https://raw.githubusercontent.com/Ahoo-Wang/CoSec/refs/heads/main/schema/cosec-snapshot-document.schema.json"
    }
  ]
}
```

The files are generated from the format descriptors in `cosec-core`
([`DocumentFormats`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/snapshot/format/DocumentFormats.kt))
and never edited by hand. A test keeps them and the document reader in agreement: what one accepts, the other accepts.

Passing the schema is necessary, not sufficient. JSON Schema cannot express, among others: which of the three
documents a file is; unique ids and references between documents; whether a time zone exists, and dates such as
February 30; a time window whose `from` equals its `to`, and `notBefore` after `notAfter`; path variable scope;
the attribute catalog and the types of condition operands. Compile the document to check those
([Testing policies](./testing.md#validating-policies)).

**Next:** grant permissions by role instead of per principal, in [Role permissions](./role-permissions.md).

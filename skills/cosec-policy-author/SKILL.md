---
name: cosec-policy-author
description: Write, review, validate, or explain CoSec 8.0 policy documents (platform, app and tenant documents) - policies and statements, actions and resources, condition ASTs, bindings, permission catalogs, rate limit rules, condition limits and external attributes - and check them with the JSON Schemas, cosec-testkit scenarios, and the control plane's simulate and explain. Do not use for Spring Boot setup.
---

# CoSec Policy Authoring

Produce the smallest change to the right document that expresses the requested access rule. Before writing JSON,
establish: the actions (route-map `service:verb`, or `http:<METHOD>` for unmapped requests) and resources, who the rule
is for (anyone, a principal, a role), in which tenant, app and space, and the explicit deny cases.

The human-facing reference is https://cosec.ahoo.me/policies/language. In a CoSec checkout the format is defined in
`cosec-core/src/main/kotlin/me/ahoo/cosec/snapshot/format/PolicyDocumentFormats.kt` (generated into
`schema/cosec-snapshot-document.schema.json`, `cosec-binding.schema.json`, `cosec-catalog.schema.json`) and read by
`PolicyDocumentCodec`; conditions are checked by `model/.../condition/ConditionChecker.kt`. Treat those as the truth.

## Documents

Policies live in three kinds of document, each one JSON object stored as one Redis key (the snapshot):

| Document | Key | Holds |
|---|---|---|
| Platform | `platform` | Global policies and bindings, `boundaries`, the condition `limits` and the external `attributes` every document compiles against. Exactly one; without it every request fails closed (503). |
| App | `app:<appId>` | The app's permission `catalog`, its permission policies `perm:<appId>/<permission>`, and role bindings. |
| Tenant | `tenant:<tenantId>` | The tenant's own policies and bindings, and optionally which platform `boundary` holds it. |

Every document starts with the same header:

```json
{ "schema": 2, "revision": 1, "publishedAt": "2026-10-28T00:00:00Z", "policies": [], "bindings": [], "rateLimits": [] }
```

`schema` is always `2`; `revision` is positive and grows by one per publish; `provenance` is written by the control
plane. Reading is strict: an unknown field, a repeated key, a wrong JSON type or another schema is an error naming the
JSON path. A document compiles whole or not at all; one that does not compile is never installed, and every request
that needs it fails closed.

## Minimal platform document

```json
{
  "schema": 2, "revision": 1, "publishedAt": "2026-10-28T00:00:00Z",
  "policies": [
    { "id": "public", "statements": [
      { "name": "health", "effect": "allow", "action": "http:GET", "resource": "http:path:/actuator/health" },
      { "name": "login", "effect": "allow", "action": "http:POST", "resource": "http:path:/auth/login" } ] },
    { "id": "members", "statements": [
      { "effect": "allow", "action": "*", "condition": { "eq": ["subject.authenticated", true] } } ] },
    { "id": "no-admin-api", "statements": [
      { "effect": "deny", "action": "http:*", "resource": "http:path:/api/admin/**",
        "condition": { "not": { "in": ["admin", "scope.roles"] } } } ] }
  ],
  "bindings": [
    { "id": "public", "subject": "*", "app": "*", "space": "*", "policy": "public" },
    { "id": "members", "subject": "*", "app": "*", "space": "*", "policy": "members" },
    { "id": "no-admin-api", "subject": "*", "app": "*", "space": "*", "policy": "no-admin-api" }
  ]
}
```

A policy does nothing until a binding (or a token's `policies` claim, for an `attachable` policy) names it.

## Policies and statements

- Policy: `id` (required, unique in the document), `statements` (required), optional `condition` (checked once,
  before its statements; it cannot read `resource.var.*`), `attachable` (default `false`: a principal may list it in
  its token's `policies` claim), `boundary` (platform only, see below).
- Statement: `effect` (`allow` or `deny`, required), `action` (one pattern or a non-empty array, required),
  `resource` (one or an array; absent means any resource), `condition`, `name`, and `onMissing` for external
  attributes.
- Ids starting with `cosec:` are built in: `cosec:blacklist`, `cosec:root` (break-glass only),
  `cosec:platform-admin`, `cosec:tenant-admin`, `cosec:tenant-viewer`. No document defines them. Policy and binding
  ids starting with `cosec:`, `g:` or `r:` are reserved (`cosec-migrate` writes `g:` and `r:`): the control plane
  rejects a change that adds one (422).

### Actions and resources

- Action: `service:verb`, `service:*` or `*`; compared exactly, case included. HTTP actions are `http:GET`,
  `http:POST`, ..., and `http:*`.
- Resource: a typed pattern `type/segment/...` where a segment is a literal, `*` (one non-empty segment), `{name}`
  (one segment, captured) or a final `**`; or `http:path:<pattern>` (Spring-like: literals, `?`, `*`, `{name}`,
  `**` and `{*name}` at the start or end; no `{name:regex}`). An object form adds `caseSensitive` and
  `decodeAndParseSegments` (both default `true`) to an `http:path:` pattern.
- A DENY on a bare type (`order`) does not compile: it would only cover the empty id. Write `order/**`.
- How a request gets its action: on WebFlux and WebMvc hosts a route map (`cosec-routes.json`) maps method and path to
  `service:verb` on a typed resource (`order/{orderId}`); any request no route matches keeps `http:<METHOD>` on
  `http:path:<path>`. A Spring Cloud Gateway host loads no route map: every request is in the `http` namespace.
  **A statement on `http:*` never applies to a route-mapped request, and a `service:verb` statement never applies to an
  unmapped one.** When a route map is in use, write DENYs on the mapped actions, or on `*`.
- Captured variables (`{orderId}` in a resource, or in the route map's path) are read as `resource.var.orderId`, only
  by the statement whose own patterns capture them, and only if every resource pattern of that statement captures it;
  otherwise the statement does not compile. Values are compared with `eq`; they never become part of a pattern.

## Conditions (AST)

One object with exactly one operator. Literals keep their JSON type: `"true"` is not `true`.

| Operator | Form | Notes |
|---|---|---|
| `eq` | `{"eq": [attr, value]}`, optional sibling `"ignoreCase": true` | value is a string, a boolean, or `{"attr": path}` of the same type; string, bool and IP attributes |
| `in` | `{"in": [attr, [values]]}` or `{"in": ["literal", setAttr]}` | value is one of them; or the set contains the literal |
| `prefix` | `{"prefix": [attr, "text"]}`, optional `ignoreCase` | strings; on `*.host.labels` a prefix of whole labels (`com.example`) |
| `cidr` | `{"cidr": [ipAttr, ["10.0.0.0/8", ...]]}` | IPv4 and IPv6 blocks |
| `time` | `{"time": {...}}` | at least one of `notBefore` (inclusive), `notAfter` (exclusive), `daysOfWeek` (`MON`..`SUN`), `from`/`to` (`HH:mm`, may cross midnight); `zone` default `UTC` |
| `exists` | `{"exists": attr}` | the attribute has a value (the empty string counts) |
| `and`, `or` | `{"and": [c, ...]}` | at least one; left to right, short-circuit |
| `not` | `{"not": c}` | |

There is no `true`/`false` condition, no expression language (no SpEL, no OGNL, no regex), and no `negate` flag:
use `not`. Attributes:

- `subject.id`, `subject.tenant`, `subject.authenticated` (bool), `subject.roles` (bare role ids in any space),
  `subject.scopedRoles` (`role` / `role@space` as claimed), `subject.attributes.<name>` (token or API key attributes);
- `scope.tenant`, `scope.app`, `scope.space`, `scope.roles` (the roles in effect in the request's space);
- `action.service`, `action.verb`, `resource.type`, `resource.id`, `resource.attributes.<name>` (set by checks in
  code), `resource.var.<name>`;
- `environment.ip` (IP), `environment.time`, `environment.device`, `environment.blocked` (bool);
- `http.header.<name>` (name case-insensitive), `http.origin`, `http.referer`, `http.origin.host`,
  `http.referer.host` (lower case), `*.host.labels`, `request.attributes.<name>` (the gateway sets
  `request.attributes.cosec.audit.routeId`, its route id, and `cosec.identity.forward` = `false` on routes that opt out
  of identity propagation; WebFlux and WebMvc set none). A header sent more than once
  with different values has no value: a DENY on it is Indeterminate (503), an ALLOW on it does not match; identical
  copies read as one. **No attribute reference may read a credential header**, neither a condition nor a rate limit `key`:
  `http.header.authorization`, `proxy-authorization`, `cookie`, `set-cookie`, `x-api-key`, `cosec-identity` (any case)
  do not compile. Headers are client input:
  never treat one as identity;
- `ext.<provider>.<name>`: external attributes (below).

An unknown path, a type mismatch, or a literal that is not an IP or CIDR does not compile. An **absent** attribute makes
`eq`, `in`, `prefix`, `cidr` and `exists` false; `not` makes that true, so an ALLOW written as `not ...` also allows
requests without the attribute. Prefer an ALLOW on the positive fact, and put negations in DENYs.

Limits (platform `limits`, defaults): `maxNodes` 64, `maxDepth` 8, `maxInValues` 1024, `maxCidrBlocks` 256,
`maxLiteralBytes` 1024, `maxCost` 256 per statement condition. Every document compiles against the platform's limits.

### External attributes

`ext.<provider>.<name>` is fetched per request by an `AttributeProvider` registered in the service (for example
`ext.ip2region.country` with the ip2region feature, or `ext.blacklist.*`). A path compiles only if a registered
provider declares it or the platform document declares it in `attributes`:

```json
"attributes": [ { "path": "ext.risk.score", "type": "string" } ]
```

Types are `string`, `bool`, `stringSet`, `ip`. Instances and the control plane compile against their own providers
plus the platform's declarations; the data plane still needs the provider to fetch it, or the attribute is
unavailable. Providers (and their types) must be the same on every instance and on the control host: a candidate an
instance cannot compile reports `CANDIDATE_ERROR`, blocks its shadow window and is never published. When a statement's
external attribute cannot be fetched (provider failed, timed out, not registered), `onMissing` decides:
`indeterminate` (default) makes the request 503 unless a DENY matches; `nomatch` treats the statement as not matching.
`onMissing` is allowed only on a statement whose condition reads an `ext.` attribute. A DENY with `nomatch` silently
stops denying during an outage, so it needs a confirmation the control plane records (`onMissingConfirmed`); never
write that field by hand.

## Bindings

`"[subject] is subject to [policy] in [app] and [space]"`:

```json
{ "id": "editor-read", "subject": { "role": "editor" }, "app": "order-service", "space": "*", "policy": "perm:order-service/read" }
```

- `subject`: `"*"` (anyone, anonymous included), `{"principal": "...", "tenant": "..."}` or `{"role": "...", "tenant": "..."}`.
- `app`: an app id or `"*"` (never empty). `space`: a space id, `""` for the default space only, or `"*"` for any space.
  `"*"` means any and only that.
- `expiresAt` (ISO instant): the binding stops applying then. `breakGlass`: see below.

Where each binding may live:

| Document | Subjects | Policies it may bind |
|---|---|---|
| Platform | `*`, principals (tenant required); no roles | its own policies, built-ins |
| App | roles only (tenant absent = any tenant); app must be this app or `*` | its own `perm:<app>/...` policies |
| Tenant | `*`, principals and roles of this tenant (tenant may be omitted) | its own, the platform's, and `perm:<app>/...` scoped to exactly that app |

A document binds at most 1,000 principals: grant users through roles in tokens or a membership provider, not
principal bindings. Binding ids are unique per document. A tenant may not define a policy id the platform also defines:
such an id is ambiguous and requests that need it fail closed.

## App documents and catalogs

```json
{
  "schema": 2, "revision": 1, "publishedAt": "2026-10-28T00:00:00Z",
  "catalog": { "groups": [ { "name": "order", "description": "Orders", "permissions": [
    { "id": "read", "name": "Read" }, { "id": "ship", "name": "Ship" } ] } ] },
  "policies": [
    { "id": "perm:order-service/read", "statements": [ { "effect": "allow", "action": "order:read", "resource": "order/**" } ] }
  ],
  "bindings": [
    { "id": "viewer-read", "subject": { "role": "viewer" }, "app": "order-service", "space": "*", "policy": "perm:order-service/read" }
  ]
}
```

Policies of an app document are named `perm:<appId>/<permission>` and cannot be attachable. The catalog is for UIs and
generators only; it takes no part in evaluation (`spaced` is informational). The OpenAPI feature generates a route map and
an app document (`GET /actuator/cosecServiceGenerator/routes` and `/app`). Role grant semantics:
https://cosec.ahoo.me/policies/role-permissions.

## Rate limit rules

`rateLimits` in any document. A permitted request spends one permit of every rule it matches (document order: platform,
app, tenant; then rule order); denied requests spend nothing; the first exhausted rule answers 429 with `RateLimit-*`
and `Retry-After`.

```json
{ "id": "login-per-ip", "action": "http:POST", "resource": "http:path:/auth/login",
  "condition": { "eq": ["subject.authenticated", false] },
  "key": "environment.ip", "permitsPerSecond": 0.1, "windowSeconds": 60, "strictFailure": true }
```

`id` is `[A-Za-z0-9_.-]+`, unique per document. Each document counts in its own buckets (a platform rule under its
id, others under `app:<app>/<id>` and `tenant:<tenant>/<id>`), and rules add up: a tenant rule never replaces or
relaxes a platform or app rule, even with the same id. `action` is
required; `resource`, `condition` as in a statement, matched as a DENY is (so a bare resource type does not compile).
`key` names a string or IP attribute (one bucket per value; absent key = one shared bucket; absent value = the empty
bucket); a `resource.var.*` key must be captured by every resource pattern. A window allows
`permitsPerSecond x windowSeconds` (default 1) requests. When the limiter cannot count, `strictFailure: false` (default)
skips the rule and `true` answers 503. `onMissing: nomatch` on a rule compiles in the platform document only (rules
have no confirmation flow).

Change rules through the control plane: `putRateLimit` / `removeRateLimit` operations, or `:apply` (rule order is
content). Each needs `cosec:write` on `doc/<document>/rateLimit/<id>`. A tenant's own rule changes need no approval; a
shadow window compares decisions, so it shows nothing for a change that only touches rate limits.

## Boundaries and break-glass

- Boundary: a platform policy with `"boundary": true`, ALLOW statements only (at most 64), never bound or attached.
  `boundaries.default` in the platform names the one that holds every tenant; a tenant document's `boundary`
  (written only by platform administrators) names another (the platform tenant has none). A permit stays a permit only if a boundary statement matches.
- Break-glass: a platform binding with `"breakGlass": true` and `expiresAt`, binding a principal of the `(platform)`
  tenant; the only way to bind `cosec:root`. Request it through the control plane (`POST /break-glass`), never by
  editing a document.

## Evaluation semantics

1. The built-in blacklist DENY, then the DENY statements of every candidate: platform `*` bindings, tenant `*`
   bindings, the principal's attachments and principal bindings, role bindings of the app document then the tenant
   document (only roles in the request's space, only bindings whose app and space match), break-glass bindings.
   The first matching DENY decides: **any matching DENY wins over every ALLOW**, whatever the document or order.
2. If no DENY matched but a DENY errored, a needed document failed or is absent, or a needed policy is missing or
   ambiguous: Indeterminate (503).
3. Otherwise the first matching ALLOW permits; an erroring ALLOW counts as no match. Nothing matched: implicit deny.
4. A permit is then held to the tenant's boundary, if any.

Responses: deny is 401 for an anonymous caller and 403 for an authenticated one; Indeterminate is 503; an exhausted
rate limit is 429. A policy-level `condition` that is false skips the policy; it never denies. Full model:
https://cosec.ahoo.me/guide/security-model.

## Security review

Check the final document for:

- broad `subject: "*"` ALLOWs that grant more than intended (`*` includes anonymous callers);
- `http:*` DENYs on a service that uses a route map (they never apply to mapped requests);
- ALLOWs built on `not`, or on attributes that can be absent (headers, `request.attributes.*`);
- missing method constraints (`http:*` where only `http:GET` was meant) on state-changing endpoints;
- `space: "*"` where one space was meant (`""` is the default space only);
- tenant-supplied facts trusted as identity: `CoSec-App-Id`/`CoSec-Space-Id` are client input (a space needs a role
  there, and a host with `cosec.authorization.scope.app-id` rejects another app);
- `environment.ip` behind proxies (it depends on `cosec.authorization.remote-ip.max-trusted-index`);
- a DENY with `onMissing: nomatch` (it lapses during a provider outage);
- rate limit rules without `strictFailure` where an outage must not lift the quota.

## Validation and rollout

1. **Schema:** validate the document against `schema/cosec-snapshot-document.schema.json` (its `platformDocument`,
   `appDocument` and `tenantDocument` definitions). The schema cannot check unique ids, binding placement, attribute
   types or limits; compiling does.
2. **Compile:** in Kotlin, `PolicyDocumentCompiler.forPlatform(platform).compile(PolicyDocumentCodec.decode(kind,
   owner, bytes))` throws `DocumentCompileException` listing every error with its JSON path;
   `checkReferences(document, platform)` checks that bound policies exist.
3. **Test decisions** in the service's own test suite with `cosec-testkit`
   (`testImplementation("me.ahoo.cosec:cosec-testkit")`): keep the documents as `cosec-migrate convert` writes them
   (`platform.json`, `apps/<app>.json`, `tenants/<tenant>.json`, ids URL-encoded) and one scenario per JSON file, then
   `PolicyTestKit.load(documentsDir).tests(scenariosDir)` from a JUnit 5 `@TestFactory`. A scenario is
   `{"description", "credential": {"tenant", "id", "roles", "policies", "attributes"}, "request": {"action",
   "resource": {"type", "id"}, "app", "space", ...}, "expect": {"decision": "Permit"}}`; only the fields `expect` names
   are compared, and `"by"` pins the deciding statement (`"by": null` is the implicit deny). Write one scenario per
   meaningful allow/deny boundary, and one per explicit DENY with its `by`. The kit does not run rate limits or route
   maps, and documents that read `ext.*` do not load in it. https://cosec.ahoo.me/policies/testing#testing-decisions
4. **Simulate and explain** through the control plane (`controlSupport`), as an operator allowed to:
   `POST /cosec/control/v1/simulate` with
   `{"request": {"tenant": "acme", "principal": "alice", "roles": ["editor"], "app": "order-service", "space": "", "action": "order:read", "resource": "order/42"}, "candidate": "<changeId>"}`
   compares a staged change with the enforced documents; `POST /explain` shows the deciding statement. External
   attributes are not fetched in a simulation, so a statement reading one is Indeterminate there.
5. **Publish** through the control plane: `POST /documents/{key}:apply` (`mode` `MERGE` or `REPLACE`) or
   `POST /changes`, optionally a shadow window before publishing. A compile error is 422, a stale base revision 409,
   a `nomatch` DENY without confirmation 428. See https://cosec.ahoo.me/policies/testing. The first documents (and
   7.x data) are published with `cosec-migrate publish`.

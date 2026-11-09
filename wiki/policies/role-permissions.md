---
title: "Role Permissions"
description: "Describe an app's permissions in its app document and grant them to roles, per app and per space."
---

# Role Permissions

For product teams and administrators who manage access through roles rather than per-user policies. This page shows
how an app describes its permissions once, how they are granted to roles, and how a grant is limited to an app and a
space. Every field is defined in the [policy language](./language.md).

An app publishes one **app document**: a catalog of what can be done in it, its permissions, and the roles and role
bindings that grant them. An entry of the catalog can define its permission itself ([short-form permissions](#short-form-permissions)),
and a [role](#roles) is written once with the permissions it holds. A principal's token (or `MembershipProvider`) says which roles it holds
in which space; a request is granted what the role bindings give the roles it holds in the request's space. Tenants
can add grants of their own in their tenant documents.

## App documents

The app document of app `order` has the key `app:order`. It holds a [catalog](#permission-catalogs),
[permission policies](#permission-policies) and [role bindings](#granting-permissions-to-roles), and may hold
[rate limit rules](./rate-limiting.md); it defines no other policies and binds nothing but roles.

```json
{
  "schema": 2,
  "revision": 1,
  "publishedAt": "2026-11-01T08:00:00Z",
  "catalog": {
    "groups": [
      {
        "name": "order",
        "description": "Orders",
        "spaced": true,
        "permissions": [
          { "id": "read", "name": "Read orders" },
          { "id": "ship", "name": "Ship orders" }
        ]
      }
    ]
  },
  "policies": [
    {
      "id": "perm:order/read",
      "statements": [{ "name": "read", "effect": "allow", "action": "order:read" }]
    },
    {
      "id": "perm:order/ship",
      "statements": [{ "name": "ship", "effect": "allow", "action": "order:ship", "resource": "order/{orderId}" }]
    }
  ],
  "bindings": [
    {
      "id": "viewer-read",
      "subject": { "role": "viewer" },
      "app": "order",
      "space": "*",
      "policy": "perm:order/read"
    },
    {
      "id": "clerk-ship",
      "subject": { "role": "clerk" },
      "app": "order",
      "space": "*",
      "policy": "perm:order/ship"
    }
  ]
}
```

A request reads the app document of its app when the principal holds at least one role in the request's space, or
when its tenant's document binds one of the app's permission policies to `Anyone` or to a principal
([Tenant role bindings](#tenant-role-bindings)). The app is the host's: on a gateway, the route's metadata
`cosec-app-id`, else `cosec.authorization.scope.app-id`; without one, the app the request declares in
`CoSec-App-Id`. Who controls these inputs is in the
[security model](../guide/security-model.md#trust-boundaries). A request without an app reads no app document, so no
app role binding applies to it.

## Permission catalogs

The `catalog` lists the app's permissions in named groups, for consoles that let administrators pick what to grant
([fields](./language.md#catalogs)). An entry with `action` also defines its permission: see [short-form
permissions](#short-form-permissions). Any other entry takes no part in evaluation, as nothing grants through it, and
nothing checks that it has a policy. `spaced` says where a short-form permission applies.

## Permission policies

Each permission is a policy named `perm:<appId>/<permission>`, such as `perm:order/read`. Its statements say what the
permission allows, like any other policy.

- Only the app's own document defines its permission policies, and every policy of an app document is one.
- A permission policy cannot be attachable: it is reached only through role bindings.
- The platform and tenant documents define none.

## Granting permissions to roles

A role binding in the app document grants a policy to a role:

| Field | In an app document |
|---|---|
| `subject` | `{"role": "<roleId>"}` for that role in any tenant, or `{"role": "<roleId>", "tenant": "<tenantId>"}` for one tenant. |
| `app` | The app's own id, or `*`. A binding scoped to another app does not compile. |
| `space` | A space id, `""` for the default space, or `*` for any space. |
| `policy` | One of the app's permission policies, or a platform policy. Another app's permission policy does not compile. |

A role id is compared exactly, case included, with the roles the principal holds.

## Role grant scope

A role binding applies to a request when all of these hold:

1. The principal holds the role in the request's space ([Spaces](#spaces)).
2. The binding's `app` is `*` or the request's app.
3. The binding's `space` is `*` or the request's space (`""` is the default space).
4. The binding names no `tenant`, or the principal's tenant.
5. It has not expired (`expiresAt`).

| Binding `space` | Applies in |
|---|---|
| `""` | the default space only |
| `"team-a"` | space `team-a` only |
| `"*"` | every space in which the principal holds the role |

`*` widens only where a binding applies, never which roles count: a role held in the default space does not apply in
space `team-a`, even through a `space: "*"` binding. An app document is read only for its own app, so in it `app: "*"`
and the app's own id mean the same.

## Spaces

A request's space is the `CoSec-Space-Id` parameter; without one, the default space `""`. The principal's roles come
from its token's `roles` claim (or its `MembershipProvider`): `viewer` is a role in the default space, `clerk@team-a`
a role in space `team-a` ([Authentication](../integration/authentication.md)).

| Token `roles` | Request space | Roles that count |
|---|---|---|
| `["viewer", "clerk@team-a"]` | default | `viewer` |
| `["viewer", "clerk@team-a"]` | `team-a` | `clerk` |
| `["viewer", "clerk@team-a"]` | `team-b` | none: the request is refused |

A request that declares a space in which the principal holds no role is refused with `403` (`scope-rejected`), never
evaluated in the default space; an anonymous request that declares a space is answered `401`. `*` is never a space
id. Why the space is safe to take from the client is in the
[security model](../guide/security-model.md#trust-boundaries).

## Tenant role bindings

A tenant grants roles in its own tenant document, without touching the app document:

```json
{
  "schema": 2,
  "revision": 4,
  "publishedAt": "2026-11-01T08:00:00Z",
  "bindings": [
    {
      "id": "auditor-reads-orders",
      "subject": { "role": "auditor" },
      "app": "order",
      "space": "",
      "policy": "perm:order/read"
    }
  ]
}
```

- A role subject without `tenant` is a role of this tenant; naming another tenant does not compile.
- The policy can be one of the tenant's own policies, a platform policy, or an app's permission policy. An app's
  permission policy needs `app` set to exactly that app: it is then taken from that app's document, read in the same
  request. Any other binding of a permission policy does not compile.
- A tenant document may bind an app's permission policy to `Anyone` or to one of its principals too, scoped to that
  app the same way. A request in that app then reads the app's document whatever the principal's roles, so the
  binding resolves and its DENY or ALLOW applies; it also brings in the app document's
  [rate limit rules](./rate-limiting.md).
- Tenant role bindings follow the same [scope rules](#role-grant-scope).

## Short-form permissions

A catalog entry that writes `action` defines its permission in one line. It is the policy `perm:<appId>/<id>`: one
ALLOW statement of that action and `resource`, not attachable.

```json
{
  "catalog": {
    "groups": [
      {
        "name": "Warehouse",
        "permissions": [{ "id": "warehouse.read", "name": "Read warehouses", "action": "wms:warehouse.read" }]
      },
      {
        "name": "Inbound",
        "spaced": true,
        "permissions": [
          { "id": "inbound.ship", "name": "Ship", "action": "wms:inbound.ship", "resource": "inbound/{id}" }
        ]
      }
    ]
  }
}
```

A permission in a group that is not `spaced` applies only in the default space: its policy carries
`{ "eq": ["scope.space", ""] }`. A permission in a `spaced` group applies in any space a role holding it applies in,
the default space included.

- A permission has one definition: an entry with `action` and a policy of its id do not compile. Write a policy when a
  permission needs a condition, a DENY or several statements; `spaced` does not apply to it, so its author writes the
  restriction.
- The action must be one a request resolves to: a [route map](./language.md#route-maps), a generated OpenAPI map, or a
  check in code. A service without route maps can write HTTP directly: `"action": "http:GET"`,
  `"resource": "http:path:/orders/**"`.
- An entry with neither an `action` nor a policy stays for display only, as before; no role can list it.

## Roles

A role has a place in a document: an id, a name, a description, and the permissions it holds. It can have no
permissions yet. A member holds a role by its id, in a space, through its token's `roles` claim or its
`MembershipProvider`, as with any other role.

The app document holds the app's **system roles**:

```json
{
  "roles": [
    { "id": "wms.clerk", "name": "Clerk", "description": "Works the warehouse", "permissions": ["warehouse.read"] },
    { "id": "wms.admin", "name": "Administrator", "permissions": "*" },
    { "id": "wms.draft", "name": "To be set up", "permissions": [] }
  ]
}
```

A tenant document holds that tenant's **custom roles**, each of one app:

```json
{
  "roles": [
    { "id": "night-shift", "app": "wms", "name": "Night shift", "permissions": ["warehouse.read"] },
    { "id": "wh-01-keeper", "app": "wms", "space": "wh-01", "name": "Keeper of warehouse 1", "permissions": ["inbound.ship"] }
  ]
}
```

| Field | Documents | Meaning |
|---|---|---|
| `id` | both, required | Unique in the document. Not `*`, without `@`, which separates a membership's role and space. Prefer opaque ids. |
| `app` | tenant, required | The app the role belongs to. A role grants only in its own app. |
| `space` | tenant, optional | Absent: any space a member holds the role in. Otherwise only that space; `""` is the default space. Not a reserved id. |
| `name`, `description` | both, optional | For display. |
| `permissions` | both, required | Catalog ids, possibly none. `"*"`, every permission the catalog defines, only in an app document. |

- A system role applies to every tenant, in any space a member holds it. It names no space: spaces belong to tenants.
- `"*"` follows the catalog: permissions added later reach the role, which the change's diff and approval show as new
  bindings. A tenant's role cannot write it, or an app's publish would widen every tenant's role unseen.
- Listing a permission that is not defined does not compile in an app document. A tenant document compiles without the
  app's, so there the control plane reports it (`REFERENCES`); on the data plane a role whose permission has gone is
  indeterminate, so its requests fail closed.
- A role with a space that lists a permission of a group that is not `spaced` gets a warning: it never applies there.
  So does a tenant's role with the id of one of its app's system roles: a member holding it gets both.
- A role has no `expiresAt`, is not break-glass, and cannot bind a principal or `Anyone`: write [bindings](#granting-permissions-to-roles)
  for those. Both forms may grant the same role; the result is the union.

A role is not stored as bindings. Everything that reads what a document grants expands it first: one binding per
permission, `role:<roleId>@<app>:<permission>`, after the hand-written ones. Compilation, the grant analysis, the
reference index, the diff, `simulate`, `explain` and `analyze` all read that expansion. Roles may derive at most
**50,000** bindings per document; more does not compile, at `$.roles`.

### What a grantor needs

A `putRole` needs `cosec:write` on the role (`doc/tenant/<t>/role/<id>`). When it adds a binding it did not derive, it
also needs `cosec:grant` on its one scope; `removeRole` and a `putRole` that only removes permissions need only
`cosec:write`. So an administrator can be narrower than `cosec:tenant-admin`:

| Administrator | `cosec:write` | `cosec:grant` |
|---|---|---|
| Permission editor | `doc/tenant/<t>/role/*` | `scope/<t>/*/*` |
| Space administrator | `doc/tenant/<t>/role/*` | `scope/<t>/wms/wh-01` |

The [grant analysis](./testing.md) splits a role by permission, and `NOT_COVERED` names the one it could not prove:

| What the role grants | Scope | The grantor needs |
|---|---|---|
| A permission that is not `spaced` | The default space (its condition narrows it) | To hold that permission in the default space |
| Any permission of a role with space `S` | `S` | To hold it in `S` |
| A `spaced` permission of a role without a space | Every space | To hold it in every space: through an attachment or a `space: "*"` principal binding |

A permission held through a role counts only in the spaces the role is held in, so it never covers "every space":
the third row is `UNKNOWN`, and goes to approval.

A tenant's owner is best expressed as an attachment: the token's `policies` claim names an attachable policy, which the
analysis counts for every app and every space. Who becomes the owner is the identity system's to decide, and needs no
document change.

## Syncing roles from an external source

Roles and permissions may be owned elsewhere, such as in an event-sourced identity system. CoSec's documents are then a
projection, and they are still written **only through the control plane**: writing the cache directly would skip
validation, compilation and audit.

| The source changes | Control-plane call |
|---|---|
| A role's permissions | `putRole`, without `ifMatch` |
| A role is deleted | `removeRole` |
| An app's permissions | `putCatalog` |
| Periodic reconciliation | `apply` with `MERGE`; `REPLACE` when the source is the only writer |

- Each change goes `propose`, `skip-shadow`, `publish`. The caller is a platform service principal holding
  `cosec:approve`, `cosec:skip-shadow` and `cosec:publish`: the trust root ([Testing policies](./testing.md)). The source
  decides who may grant, and CoSec records the audit.
- `putRole` replaces a role whole, so replaying one is idempotent. A conflict (`409`) is the projection's to retry.
- `skip-shadow` takes the document's staging slot, and a change someone else is shadowing holds it: do not have people
  and a sync change the same document.

## Storing catalogs and grants

App documents belong to the platform: only principals of the `(platform)` tenant may change them, while a tenant's
principals change only their own tenant document. Publish a document through the control plane, either whole
(`POST /documents/app:order:apply`) or as entry operations (`putCatalog`, `putPolicy`, `putBinding`, `putRole`), or with
`cosec-migrate publish` for a first revision. Changes are checked, compiled and can be shadowed before they publish:
see [Testing policies](./testing.md) and [Storage](../operations/storage.md).

## Generating a catalog from OpenAPI

With `cosec-openapi` and springdoc, the `cosecServiceGenerator` actuator endpoint generates both halves of a service's
setup from the OpenAPI document springdoc scans:

- `cosecServiceGenerator/routes`: the [route map](./language.md#route-maps).
- `cosecServiceGenerator/app`: the app document.

The service is `info.x-cosec-service`, or else `info.title`. Each operation's action is `<service>:<operationId>`, or
its `x-cosec-action`. The app document gets one permission policy `perm:<service>/<verb>` per action, allowing that
action on any resource, and a catalog with one group per operation's first tag (`default` without one). It has no role
bindings: add them, review the document, and publish it as above. Generation fails on an operation without an action,
an `operationId` used twice, or two operations one request could match.

A path whose Path Item is a `$ref` (OpenAPI 3.1) generates what the item it names generates: a reference to
`#/components/pathItems/<name>` is followed, through further references. Generation fails, naming the path and the
reference, when the target does not exist, the references loop, the reference points anywhere else (another file, a URL,
another part of the document), or the path also has operations beside the `$ref`: the OpenAPI specification leaves such a
combination undefined, and a route map that dropped or guessed an operation would grant less or more than the API
describes.

**Next:** limit how often allowed requests may run, in [Rate limiting](./rate-limiting.md).

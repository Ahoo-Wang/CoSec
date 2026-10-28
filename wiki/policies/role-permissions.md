---
title: "Role Permissions"
description: "Describe an app's permissions in its app document and grant them to roles, per app and per space."
---

# Role Permissions

For product teams and administrators who manage access through roles rather than per-user policies. This page shows
how an app describes its permissions once, how they are granted to roles, and how a grant is limited to an app and a
space. Every field is defined in the [policy language](./language.md).

An app publishes one **app document**: a catalog of what can be done in it, one permission policy per permission, and
the role bindings that grant those permissions. A principal's token (or `MembershipProvider`) says which roles it holds
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

A request reads the app document of its app only when the principal holds at least one role in the request's space.
The app is the one the host is configured for (`cosec.authorization.scope.app-id`), or else the one the request
declares in `CoSec-App-Id`; who controls these inputs is in the
[security model](../guide/security-model.md#trust-boundaries). A request without an app reads no app document, so no
app role binding applies to it.

## Permission catalogs

The `catalog` lists the app's permissions in named groups, for consoles that let administrators pick what to grant
([fields](./language.md#catalogs)). It takes no part in evaluation: removing a catalog entry revokes nothing, and
nothing checks that each entry has a policy. `spaced` only says that a group's permissions are meant to be granted per
space.

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
- Tenant role bindings follow the same [scope rules](#role-grant-scope).

## Storing catalogs and grants

App documents belong to the platform: only principals of the `(platform)` tenant may change them, while a tenant's
principals change only their own tenant document. Publish a document through the control plane, either whole
(`POST /documents/app:order:apply`) or as entry operations (`putCatalog`, `putPolicy`, `putBinding`), or with
`cosec-migrate publish` for a first revision. Changes are checked, compiled and can be shadowed before they publish:
see [Testing policies](./testing.md) and [Storage](../operations/storage.md).

## Generating a catalog from OpenAPI

With `openapiSupport`, the `cosecServiceGenerator` actuator endpoint generates both halves of a service's setup from
its OpenAPI document:

- `cosecServiceGenerator/routes`: the [route map](./language.md#route-maps).
- `cosecServiceGenerator/app`: the app document.

The service is `info.x-cosec-service`, or else `info.title`. Each operation's action is `<service>:<operationId>`, or
its `x-cosec-action`. The app document gets one permission policy `perm:<service>/<verb>` per action, allowing that
action on any resource, and a catalog with one group per operation's first tag (`default` without one). It has no role
bindings: add them, review the document, and publish it as above. Generation fails on an operation without an action,
an `operationId` used twice, or two operations one request could match.

**Next:** limit how often allowed requests may run, in [Rate limiting](./rate-limiting.md).

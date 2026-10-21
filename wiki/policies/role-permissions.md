---
title: "Role Permissions"
description: "Define an application's permission catalog and grant permissions to roles, per app and per space."
---

# Role Permissions

For product teams that manage access through roles rather than per-user policies. This page shows how to describe
an application's permissions once, grant them to roles, and scope those grants to an app and a space.

Policies attach rules to principals directly. Role permissions split the same rules in two: the application
publishes a **catalog** of what can be done, and an administrator **grants** permission ids from it to roles.
How role grants combine with policies is explained in the [security model](../guide/security-model.md#evaluation-order).

## Permission catalogs

A catalog is an `AppPermission`: the permissions of one application, in named groups.

```json
{
  "id": "order-app",
  "condition": { "authenticated": {} },
  "groups": [
    {
      "name": "orders",
      "description": "Order management",
      "permissions": [
        { "id": "order.read", "name": "Read orders", "action": { "path": { "pattern": "/orders/**", "method": "GET" } } },
        { "id": "order.manage", "name": "Manage orders", "action": "/orders/**" },
        { "id": "order.export.block", "name": "No exports", "effect": "deny", "action": "/orders/export" }
      ]
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | The app id, matched against the request's `CoSec-App-Id`. It must not be empty or contain `:`; an app whose id contains `:` can hold no grants. |
| `condition` | no | Evaluated before any permission of the app; when it does not match, the app's grants contribute nothing. Default: always. |
| `groups` | no | Named groups of permissions. Default `[]`. |
| `groups[].name` | yes | Group name, for display. |
| `groups[].description` | no | For display. |
| `groups[].permissions` | no | The permissions of the group. |

A permission is a [statement](./language.md#statements) with an id:

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Permission id, the value grants refer to. Keep ids unique within the app: a later permission with the same id replaces the earlier one. |
| `name`, `description` | no | For display. |
| `effect`, `action`, `condition` | | As in a [policy statement](./language.md#statements). |

Groups only organize the catalog for people; authorization looks at the permissions. A `deny` permission works like
a deny statement: a role granted it is denied the matching requests even if another grant allows them.

## Granting permissions to roles

A grant gives one role a set of permission ids, in one app and one space:

| Grant | Meaning |
|---|---|
| `clerk` → `["order.read"]` in `order-app` | members of `clerk` may read orders in `order-app` |
| `manager` → `["*"]` in `order-app` | `*` grants every permission the catalog of `order-app` defines, now and after it changes |

Ids the catalog does not define grant nothing. The request selects the app with `CoSec-App-Id` and the space with
`CoSec-Space-Id` ([App and space](../integration/web.md#app-and-space)); a request without an app id, or for an app
without a catalog, gets nothing from role grants.

## Role grant scope

A grant belongs to an **(app, role, space)** triple, `AppSpacedRoleId`, and applies nowhere else:

- **App.** A grant in one app never applies in another, even when both define the same permission id or the grant
  is `*`. Granting a role in two apps takes two grants.
- **Space.** The principal's roles say which space each role belongs to: `editor` is a role in the default space,
  `editor@team-a` a role in `team-a` only ([roles in tokens](../integration/authentication.md#roles-and-spaces)). A request in a space
  is evaluated with only the principal's roles in that space (`RoleScope`), with the suffix removed, and only the
  grants made in that space are read:

| Principal's roles | Request's `CoSec-Space-Id` | Roles evaluated | Grants read |
|---|---|---|---|
| `editor` | none (default space) | `editor` | `editor` in the default space |
| `editor` | `team-a` | none | none |
| `editor@team-a` | `team-a` | `editor` | `editor` in `team-a` |
| `editor@team-a` | `team-b` | none | none |
| `editor`, `editor@team-a` | `team-a` | `editor` | `editor` in `team-a` |

A role with an empty role or space, or with more than one `@`, applies nowhere. Because the client chooses
`CoSec-Space-Id`, it can only choose among the spaces its credential names; changing the header never picks up
another space's grants.

This is stricter than the [`inRole` condition](./language.md#matching-roles) in policies, where a bare role matches in
every space so that a deny cannot be escaped by naming another space.

## Spaces

A space is a partition inside an app, such as a team, a workspace or a project, in which the same role can carry
different permissions. Use spaces when a user is an `editor` in one workspace and a `viewer` in another:

1. Issue tokens whose roles carry the space: `editor@ws-1`, `viewer@ws-2`.
2. Grant permissions per space: `editor` in `ws-1`, `viewer` in `ws-2`.
3. Clients send the workspace as `CoSec-Space-Id`.

Without spaces, use bare roles and grant them in the default space.

## Storing catalogs and grants

With `cache-support`, catalogs and grants are read from Redis through two caches, `AppPermissionCache` (by app id)
and `RolePermissionCache` (by `AppSpacedRoleId`). CoSec only reads them; your administration service writes them
through these beans, so that every instance drops its local copy:

```kotlin
val catalog = CoSecJsonSerializer.readValue(json, AppPermission::class.java)
appPermissionCache.put(catalog.id, catalog)
rolePermissionCache.put(AppSpacedRoleId(appId = "order-app", roleId = "clerk"), setOf("order.read"))
rolePermissionCache.put(AppSpacedRoleId(appId = "order-app", roleId = "clerk", spaceId = "team-a"), setOf("*"))
```

`AppSpacedRoleId` rejects an app id containing `:` and a role id containing `@`, so no write can produce a key that
reads back as another app's or space's grant. Values carry CoCache's binary header, so writing them with plain Redis
commands does not work. The key layout, and migrating 6.x grants that were not scoped to an app, are in
[Storage](../operations/storage.md#redis) and [Upgrading](../operations/upgrading.md#migrating-role-grants).

To check grants before they reach Redis, write them into a [policy test suite](./testing.md#policy-test-cases).
To see why a role grant did or did not apply to a request, [explain the decision](./testing.md#debugging-a-decision-with-explain);
its `NO_APP_PERMISSION` and `NO_ROLE_IN_SPACE` warnings name the two most common causes.

## Generating a catalog from OpenAPI

With `cosec-openapi` and Spring Boot Actuator, `GET /actuator/cosecAppPermissionGenerator` turns the application's
OpenAPI description into a catalog draft:

| Catalog | From |
|---|---|
| `id` | `info.title`, with every `:` replaced by `-` (an app id with `:` could hold no grants); `AppId` when there is no title |
| one group per tag | the operation's tags; an operation with several tags appears in each, an operation without tags is left out |
| permission `id` | `operationId` (`""` when missing) |
| permission `name`, `description` | `summary`, `description` |
| permission `action` | a `path` action with the operation's path and method |

Every permission is an `allow`. Give every operation an `operationId` and a tag before generating, review the
result, then store it as above.

**Next:** limit how often allowed requests may run, in [Rate limiting](./rate-limiting.md).

---
title: "Apps With Spaces"
description: "Use spaces for one app with many warehouses or teams, and for one space shared by several apps: roles, tokens, the CoSec-Space-Id parameter and the documents, with checked examples."
---

# Apps With Spaces

For a team whose app has partitions that carry their own access, such as warehouses, projects or stores, or whose
partitions are shared by several apps. This page covers both shapes with documents that run: **one app with many spaces**
(per app) and **one space used by several apps** (shared). A space is an opaque string; the roles say where they apply.
Without spaces, see [An app without spaces](./app.md).

## What you need

| | Spaces |
|---|---|
| Documents | Catalogs with `spaced` groups for what applies in every space; roles with a `space` when a role belongs to one |
| Tokens | One token per app, with roles written `role@space`, issued only after checking the user belongs to the space |
| Requests | `CoSec-Space-Id: <space>` to work in a space; without it the request is in the default space |
| Concepts | The default space `""`, [scope](../guide/concepts.md#scope), `spaced`, a role's `space` |

## How a space is chosen

The token lists the spaces its principal may use, as `role@space`. The client picks one with `CoSec-Space-Id`. A space
the token does not list is refused (`403` `scope-rejected`), never read as the default space. That is a choice, not a
grant: what the principal may do in the space comes from the roles it holds there. Why this is safe to take from the
client is in [Trust boundaries](../guide/security-model.md#trust-boundaries).

## The documents

The app has a catalog with two groups. `Warehouse` is not `spaced`: its permissions apply only in the default space, so
they are operations of the whole company. `Inbound` is `spaced`: its permissions apply in any space a role holding them
applies in. Two system roles grant them, and `acme` adds a role that belongs to one warehouse.

<<< @/examples/spaces/documents/apps/wms.json [documents/apps/wms.json]

<<< @/examples/spaces/documents/tenants/acme.json [documents/tenants/acme.json]

| A role | `space` | Applies |
|---|---|---|
| `wms.manager` (system) | none | In any space the member holds it in, in every tenant |
| `wh-01-keeper` (custom) | `wh-01` | Only in `wh-01`, and only where the member holds it |

A permission of a group that is not `spaced` never applies in a named space, so a role tied to `wh-01` that lists one
gets a warning when it is saved: it would grant nothing there.

## One app, many spaces

The scenarios in [`wiki/examples/spaces`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/spaces/scenarios/per-app)
run on every build:

| Token roles | Request | Result |
|---|---|---|
| `wms.manager@wh-01` | ship inbound order 42, space `wh-01` | Permit |
| `wms.manager@wh-01` | the same, space `wh-02` | `403` `scope-rejected`: the token does not list `wh-02` |
| `wms.manager@wh-01` | read a warehouse, space `wh-01` | Deny: `warehouse.read` is not `spaced` |
| `wms.manager` | read a warehouse, no space | Permit |
| `wms.manager` | ship inbound order 42, no space | Permit: a `spaced` permission applies in the default space too |
| `wh-01-keeper@wh-01` | ship inbound order 42, space `wh-01` | Permit |
| `wh-01-keeper@wh-02` | ship inbound order 42, space `wh-02` | Deny: the role belongs to `wh-01` wherever it is held |

The login issues what the user holds, for the app the token is for:

```kotlin
TokenSubject(
    id = user.id, app = "wms", tenant = "acme",
    roles = setOf("wms.manager@wh-01", "wms.manager@wh-02", "wms.clerk"),
)
```

## One space, several apps

The space `store-1` is used by `wms` and by `pos`. Nothing about the space is declared: each app has its own roles, and a
member holds them in the same space id. Each app has its own token, because a token is for one app:

<<< @/examples/spaces/documents/apps/pos.json [documents/apps/pos.json]

| Token (issued for) | Roles | Request | Result |
|---|---|---|---|
| `wms` | `wms.clerk@store-1` | read inbound order 42, space `store-1` | Permit |
| `wms` | `wms.clerk@store-1` | ship inbound order 42, space `store-1` | Deny: the clerk does not ship |
| `pos` | `pos.cashier@store-1` | ring up a sale on `pos`, space `store-1` | Permit |
| `pos` | `pos.cashier@store-1` | any request to `wms` | `403` `scope-rejected`: the token is for `pos` |

The permissions of each app are granted by its own roles, so `pos.cashier` gives nothing in `wms`, and `wms.clerk`
nothing in `pos`. To grant one role in several apps, bind it in each app explicitly; that is a choice, not a default.

A user who works in both apps gets one token per app from the login, each with that app's roles. A client that holds
both calls each service with the matching token.

## If it fails

- A space you hold is refused: the token lists it as `role@space`, with the role written exactly as the document's id
  ([Roles and spaces](../integration/authentication.md#roles-and-spaces)).
- Permissions work in the default space and not in a named one: the group is not `spaced`
  ([Short-form permissions](../policies/role-permissions.md#short-form-permissions)).
- Roles in the token grow with the number of spaces: move the memberships to a `MembershipProvider`
  ([Memberships](../integration/authentication.md#memberships)).

**Next:** limit a machine client to one space, in [A service account in one space](./service-account.md).

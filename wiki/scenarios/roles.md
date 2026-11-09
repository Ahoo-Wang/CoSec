---
title: "System and Custom Roles"
description: "System roles in the app document, custom roles in tenant documents, and the control-plane calls behind a role console: create, save as, and save the permission matrix."
---

# System and Custom Roles

For a product team building an administration console where a tenant's administrators see the roles of an app, create
their own, copy one, and tick permissions. This page shows where each kind of role lives, how a request is granted
through it, and which control-plane call each console action is. It continues [An app without spaces](./app.md).

## Two kinds of role

| | System role | Custom role |
|---|---|---|
| Where | The app document, `roles` | A tenant document, `roles` |
| Written by | The platform, with the app | The tenant's administrators |
| Applies to | Every tenant | That tenant only |
| App | The document's | `app`, required: a custom role belongs to one app |
| Permissions | A list, or `"*"` for the catalog | A list only: `"*"` would let a catalog publish widen every tenant's role unseen |
| Can be empty | Yes | Yes |

A member holds a role by its id in its token or `MembershipProvider`, in either case. The id is the same string
everywhere, so prefer opaque ids for custom roles (a name can be renamed in the console, an id cannot).

## The documents

The app document adds a role without permissions to the one of the [previous page](./app.md#describe-the-app); a
console shows it as a role to be set up:

<<< @/examples/roles/documents/apps/wms.json [documents/apps/wms.json]

The tenant `acme` has two custom roles:

<<< @/examples/roles/documents/tenants/acme.json [documents/tenants/acme.json]

Both are written as roles, not as bindings: CoSec derives one binding per permission when it reads the document, so the
console's "permissions x of y" is the length of the role's `permissions`, and saving a role is one entry, not one
binding per permission.

## Check it

The scenarios in [`wiki/examples/roles`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/roles/scenarios) run
on every build:

| Token | Request | Result |
|---|---|---|
| `acme`, role `night-shift` | read a warehouse | Permit |
| `acme`, role `night-shift` | edit a warehouse | Deny: the role lists `warehouse.read` only |
| `globex`, role `night-shift` | read a warehouse | Deny: `night-shift` is a role of `acme`'s document |
| `globex`, system role `manager` | edit a warehouse | Permit: a system role applies to every tenant |
| `acme`, role `draft` | read a warehouse | Deny: a role without permissions grants nothing |
| `acme`, custom role `dispatcher` | ship inbound order 42 | Permit |

## The console flow

Every console action is one change on the tenant document, proposed against the revision the console showed. The
calls are on the [control API](../policies/testing.md#the-control-plane); a tenant's principals reach their own
tenant document only.

| Console action | Operation |
|---|---|
| Create a role | One `putRole` with `permissions: []` |
| Save as | One `putRole` with a new id and the copied list |
| Save the permission matrix | One `putRole` that replaces the role, optionally with `ifMatch` |
| Delete a role | One `removeRole` |

The bodies of the first three:

<<< @/examples/roles/changes/new-role.json [new-role.json]

<<< @/examples/roles/changes/save-as.json [save-as.json]

<<< @/examples/roles/changes/save-permissions.json [save-permissions.json]

`putRole` replaces the whole role, so a replay is harmless. `ifMatch`, the SHA-256 of the role entry as the document
writes it, makes the save fail with `412` if someone else changed the role first. A `base` that is no longer the
document's revision is a `409`: reload and try again.

A save is then three calls, with `$CONTROL` the control API's base (`https://host/cosec/control/v1`) and a token of the
tenant's administrator, issued for the control plane's app ([Change a document later](../guide/quick-start.md#change-a-document-later)):

```bash
ID=$(curl -s -X POST "$CONTROL/changes" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d @save-permissions.json | jq -r .id)
curl -s -X POST "$CONTROL/changes/$ID:skip-shadow" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"role matrix saved in the console"}'
curl -s -X POST "$CONTROL/changes/$ID:publish" -H "Authorization: Bearer $TOKEN"
```

1. **Propose.** The control plane validates and compiles the change, and analyzes what it grants: a permission the
   administrator does not hold is an escalation, and `NOT_COVERED` names that permission. The change lists its
   `findings` and `escalations`, so the console can show them next to the matrix.
2. **Skip the window.** `cosec:tenant-admin` may skip the shadow window on its own tenant's document, which a console
   save should: a window waits for traffic to compare. Skipping changes nothing about approval.
3. **Publish.** Without escalations the change publishes. With one, it waits for an approver (`cosec:approve`), who is a
   platform principal unless you delegate it ([Approval](../policies/testing.md#approval)).

What an administrator may grant follows from what they hold ([What a grantor needs](../policies/role-permissions.md#what-a-grantor-needs)):
a permission editor who is allowed to write roles and grant in the tenant's scopes never needs an approver, while a
space administrator is held to their space. A tenant owner holds everything through an attachment:
[A tenant owner](./tenant-owner.md).

The catalog and the system roles live in the app document, which a tenant's principal cannot read through the control
API, so the console's backend lists them with a platform principal for now.

**Next:** roles in spaces, in [Apps with spaces](./spaces.md).

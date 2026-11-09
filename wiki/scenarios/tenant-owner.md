---
title: "A Tenant Owner"
description: "Make a tenant's owner an attached policy in their token: the identity system decides who is an owner, no document changes when ownership moves, and the owner administers the tenant's roles."
---

# A Tenant Owner

For a team whose identity system already knows who owns each tenant, and that wants CoSec to follow it. The owner is
**attached** to the principal by the token (an *attachment*) instead of being bound in a document, so granting or
transferring ownership is a change in the identity system and never a change to a CoSec document. This page builds it,
and shows what an attachment does and does not give.

## What you need

| | A tenant owner |
|---|---|
| Documents | An attachable policy in the platform document; a binding of the owner role to the control plane's built-in `cosec:tenant-admin` in each tenant document |
| Tokens | The owner's token carries `policies: [tenant-owner]`; the token for the control plane carries the role `owner` |
| Concepts | [Attachments](../policies/language.md#attachments), `cosec:tenant-admin`, the grant analysis |

## The documents

The platform document defines the policy owners get. It is `attachable`, so a token may list it, and it is narrow: all
actions of the app `wms`, and nothing else.

<<< @/examples/tenant-owner/documents/platform.json [documents/platform.json]

Each tenant document binds the role `owner` once, to the built-in policy that lets a tenant administer its own
document, in the control plane's app:

<<< @/examples/tenant-owner/documents/tenants/acme.json [documents/tenants/acme.json]

The app document is the one of [An app without spaces](./app.md). The two bindings are written when the tenant is
created, and never again: who is an owner is the token's business.

The identity system issues the owner two tokens. For the app, `policies` lists the attachment:

```kotlin
TokenSubject(id = "bob", app = "wms", tenant = "acme", policies = setOf("tenant-owner"))
```

and for the control plane, issued for its app, the role:

```kotlin
TokenSubject(id = "bob", app = "cosec-control", tenant = "acme", roles = setOf("owner"))
```

Issue a token with `tenant-owner` only for a user the identity system says owns the tenant. An attachment is trusted
as the token says it, which is the point, and also why the token's issuer decides ownership.

## Check it

The scenarios in [`wiki/examples/tenant-owner`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/tenant-owner/scenarios)
run on every build:

| Caller | Request | Result |
|---|---|---|
| `bob`, attachment `tenant-owner` | edit a warehouse | Permit: no role and no binding for `bob` |
| `bob`, attachment `tenant-owner` | the same, in space `wh-01` | `403` `scope-rejected`: an attachment is not a role in a space |
| `bob`, attachment `tenant-owner` | `cosec:write` on `acme`'s document | Deny: the attachment covers `wms`, not the control plane |
| `carol`, no role, no attachment | edit a warehouse | Deny |
| `bob`, role `owner`, token for `cosec-control` | `cosec:read` on `acme`'s document | Permit: `cosec:tenant-admin` |
| `bob`, role `owner` in tenant `globex` | `cosec:read` on `globex`'s document | Deny: only `acme`'s document binds the role |

An attachment applies in every app and, by itself, only in the default space: a space still needs a role in it. Give an
owner who works in spaces the roles there too, or answer for them from a `MembershipProvider`.

## Why an attachment

The grant analysis, which decides whether an administrator may give what a change grants, counts what the author holds.
An attached policy counts for every app and every space, so an owner who holds `wms:*` through an attachment covers any
role of `wms` the tenant creates, with no approver. An administrator who holds permissions through roles is held to the
spaces they hold the roles in, so granting a `spaced` permission in all spaces goes to approval
([What a grantor needs](../policies/role-permissions.md#what-a-grantor-needs)).

So that the owner's reach stays what you intend:

- write the attached policy with the actions of the app, not `*`: an attachment applies in every app, so `*` would
  match the control plane's `cosec:` actions as well;
- keep `cosec:approve` and the platform document with the `(platform)` tenant.

**Next:** keep CoSec's roles in step with an identity system, in [Roles from an external source](./external-source.md).

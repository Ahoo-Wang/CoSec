---
title: "Simple Mode"
description: "One service, one tenant, no apps or spaces: a single platform document, the http namespace, and roles by condition. What you need and what you can leave out."
---

# Simple Mode

For a developer protecting one service for one customer, or a small product that does not need apps, spaces or
business-action names. Simple mode is the least you can run: one platform document, requests named by HTTP method and
path, and no route map. This page lists what you need, gives a document that works, and says when to move on.

## What you need

| | Simple mode |
|---|---|
| Dependencies | The [Quick start](../guide/quick-start.md#add-the-dependency)'s |
| Configuration | `spring.data.redis.*` and `cosec.jwt.secret`. `cosec.authorization.remote-ip.max-trusted-index: 0` if clients reach the service directly. Nothing for apps, spaces or a route map. |
| Files | `platform.json`; `cosec-migrate publish` writes it as the first revision |
| Redis | `maxmemory-policy noeviction` |
| Concepts | The platform document, policies, statements, bindings to everyone (`"subject": "*"`), the `http` namespace, [default deny](../guide/security-model.md#default-deny), [deny first](../guide/security-model.md#deny-first), the default tenant `(0)` |
| Left out | Apps and spaces, route maps, catalogs, role entries, boundaries, rate limits, shadow windows |

Every request is `http:<METHOD>` on `http:path:<path>`. A token carries no tenant, so its principal is in the default
tenant `(0)`, and no app, so no app document is read.

## The document

<<< @/examples/simple/documents/platform.json [documents/platform.json]

- `public` lets anyone `GET /hello`, signed in or not; `login` does the same for `POST /auth/login`.
- `members` lets a signed-in caller `GET /orders/**`.
- `admins` lets a principal with the role `admin` do anything under `/admin/**`. In simple mode the token's roles are
  roles of the default space, so `scope.roles` is the token's `roles` claim. Use `scope.roles` to authorize and not
  `subject.roles`, which also counts roles held in other spaces
  ([Roles in conditions](../guide/security-model.md#roles-in-conditions)).
- `no-delete` forbids `DELETE /orders/**` for everyone. A matching DENY wins over every ALLOW, `admin` included.
- `control-api` lets any signed-in caller reach the control API's paths. The control plane authorizes each step again as
  the caller, so this opens a path, not a permission. Without it you could never change this document through the
  control plane; see [Change a document later](../guide/quick-start.md#change-a-document-later).

Issue a token with a role when the user signs in:

```kotlin
TokenSubject(id = user.id, app = null, roles = setOf("admin"))
```

## Check it

The scenarios in [`wiki/examples/simple/scenarios`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/simple/scenarios)
run against these documents on every build, with the [test kit](../policies/testing.md#testing-decisions):

| Request | Caller | Decision |
|---|---|---|
| `GET /hello` | anonymous | Permit |
| `GET /orders/1` | anonymous | Deny: `401` |
| `GET /orders/1` | signed in | Permit |
| `DELETE /orders/1` | role `admin` | Deny: the DENY wins |
| `GET /admin/users` | role `admin` | Permit |
| `GET /admin/users` | signed in, no role | Deny: `403` |

## Many roles

A condition per role does not scale past a handful. The default tenant is a tenant like any other, so it can hold role
bindings: leave `admins` unbound in the platform document and put the binding in `tenants/(0).json` (the file name is
URL-encoded, `tenants/%280%29.json`):

<<< @/examples/simple-roles/documents/tenants/%280%29.json [documents/tenants/(0).json]

A role binding here applies to principals holding the role, and a binding to `"*"` in this document also applies to
anonymous callers, because they are in `(0)` too. The complete example, with the platform document and its scenarios,
is in [`wiki/examples/simple-roles`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/simple-roles).

## Limits of simple mode

- The platform document cannot bind a role. It is shared by every tenant, so a role there would mean the same thing to
  all of them. Use the condition above, or the `(0)` document.
- Role changes take effect when a user next logs in, as roles travel in the token
  ([Roles and spaces](../integration/authentication.md#roles-and-spaces)).
- Permissions are written as statements. If you want them listed in a console, or granted to roles by name, move to an
  app document: [An app without spaces](./app.md).

**Next:** an app with permissions and roles, in [An app without spaces](./app.md).

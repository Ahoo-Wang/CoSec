---
title: "Several Tenants"
description: "What one tenant's document does and does not do to another: a platform policy reaches every tenant, a tenant's binding stays in its tenant, and a boundary caps one tenant, shown with runnable scenarios."
---

# Several Tenants {#several-tenants}

For a deployment with more than one customer, to see where a document's reach ends. This page puts three tenants on one
platform and shows three facts, each a scenario that runs on every build: a **platform** policy reaches every tenant, a
**tenant's** binding reaches only its own principals, and a **boundary** can cap what one tenant may be permitted.

## What you need

| | Several tenants |
|---|---|
| Documents | One platform document, and one tenant document each for `acme`, `globex` and `initech` |
| Tokens | Each principal's token carries its `tenantId`: a principal is evaluated only against its own tenant's document |
| Concepts | [Tenant](../guide/concepts.md#tenant), [Platform boundary](../guide/security-model.md#platform-boundary), [Tenant isolation](../guide/security-model.md#tenant-isolation) |

## The documents

The platform document holds what every tenant shares: one policy, bound to everyone, and two boundaries.
`boundary-standard` is the default and lets `GET` and `POST` on `/api/**` stand; `boundary-read-only` lets only `GET` stand.

<<< @/examples/several-tenants/documents/platform.json [documents/platform.json]

`acme` binds its own policy to everyone, in its own document. It lets a principal of `acme` `POST` `/api/export`:

<<< @/examples/several-tenants/documents/tenants/acme.json [documents/tenants/acme.json]

`globex` binds the same kind of policy, and names `boundary-read-only` as the boundary that holds it:

<<< @/examples/several-tenants/documents/tenants/globex.json [documents/tenants/globex.json]

`initech` has a document and nothing in it, so only what the platform grants everyone reaches it:

<<< @/examples/several-tenants/documents/tenants/initech.json [documents/tenants/initech.json]

## Check it

The scenarios in [`wiki/examples/several-tenants`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/several-tenants/scenarios)
run on every build:

| Caller | Request | Result |
|---|---|---|
| `alice` of `acme` | `GET /api/orders` | Permit: the platform policy `read-orders` is bound to everyone |
| `gina` of `globex` | `GET /api/orders` | Permit: the same policy, for another tenant |
| `alice` of `acme` | `POST /api/export` | Permit: `acme`'s own binding |
| `ivan` of `initech` | `POST /api/export` | Deny, implicit: the binding is in `acme`'s document, and `initech`'s has none |
| `gina` of `globex` | `POST /api/export` | Deny, by the boundary: `globex` binds the permit, but `boundary-read-only` has no statement for `POST` |

What each says:

- **A platform binding is every tenant's.** The platform document applies to all principals, whatever their tenant;
  put in it only what every tenant may have.
- **A tenant's binding stays in its tenant.** A principal is evaluated against its own tenant's document, so `acme`'s
  `Anyone` binding means "anyone in `acme`", never anyone in `initech`. Tenants cannot see or change each other's documents
  ([Tenant isolation](../guide/security-model.md#tenant-isolation)).
- **A boundary caps; it never grants.** A permit stands only if a statement of the tenant's boundary also matches. Use it
  for a ceiling the platform sets whatever a tenant writes, such as a tenant on a read-only plan.

---
title: "Concepts"
description: "The vocabulary CoSec uses: principals, tenants, scopes, documents, policies, bindings, decisions, snapshots and the control plane."
---

# Concepts

For everyone who reads the rest of these docs. This page defines the terms the other pages use and shows how they relate, so the guides and references can link here instead of redefining them.

## The big picture

CoSec answers one question per request: may this **principal** perform this **action** on this **resource**, in this
**scope**? The answer comes from the **policies** that apply to the request through **bindings** and
**attachments**, read from a **snapshot** of the policy **documents** that the **control plane** publishes.

```mermaid
flowchart LR
    CRED[Credential] -->|verified into| PR[Principal]
    PR -->|belongs to| T[Tenant]
    PR -->|has| MEM[Memberships<br>roles per space, attachments]
    REQ[Request] -->|asks for| APPSP[Space]
    CRED -->|names| APP[App]
    HOST[Host] -->|may bind| APP
    PR --> SC[Scope<br>tenant, app, space, roles]
    MEM --> SC
    APPSP --> SC
    APP --> SC
    DOC[Documents<br>platform, app, tenant] --> POL[Policies]
    DOC --> BND[Bindings]
    BND -->|apply| POL
    MEM -->|attach| POL
    POL --> ST[Statements<br>effect, action, resource, condition]
    SC --> D[Decision<br>Permit, Deny, Indeterminate]
    ST --> D
```

The principal and its memberships come from the verified credential (or your membership provider). The request side
(method, path, headers, the space it asks for) comes from the client and is checked before it is trusted.

## Principal and tenant

### Principal

Who is making the request: an id, a tenant and free-form attributes. The principal comes from the verified credential,
a JWT access token or an API key, so the client cannot change it. A request without a valid credential has the
**anonymous** principal, id `(0)`, in the default tenant, with no roles and no attachments. Policies can allow the
anonymous principal too; that is how public endpoints work. The id `(0)` is for the security context and the decision
event: a document has no `subject.id` for an anonymous request, and a condition that compares it with `(0)`, or a
principal binding whose id begins with `(`, does not compile. Test `subject.authenticated` to tell the two apart
([Conditions](../policies/language.md#absent-values)).

### Credential

What a request presents to prove who it is: a JWT access token or an API key. CoSec verifies it into a principal; a
credential that is invalid, expired or revoked is treated as no credential, and the request is anonymous
([Authentication](../integration/authentication.md)).

### Subject

Two different things carry this word. In a **binding**, the subject is who the binding grants to: anyone (`"*"`), a
principal of a tenant, or a role. In a **condition**, `subject.*` is the principal making the request. Where it matters,
this documentation says "the binding's subject" or "the requesting principal".

### Tenant

The customer boundary the principal belongs to, taken from the credential's tenant:

| Tenant id | Meaning |
|---|---|
| `(0)` | The default tenant: anonymous principals, and credentials that name no tenant. |
| `(platform)` | The platform tenant: the operators of the system. Its principals administer the control plane, only they can be given break-glass, and no platform boundary applies to it. |
| anything else | A user tenant. |

A principal is evaluated only against its own tenant's document; principal ids are unique only within a tenant. How
far tenants are isolated is in the [security model](./security-model.md).

## Request

The transport-neutral facts of the HTTP request: method, path, query, headers, client IP, the device id, and the space the
client asks for in the `CoSec-Space-Id` parameter (see [Web integration](../integration/web.md)). All of it is chosen
by the client. The client never names the app.

### App

The application a request belongs to. Each app has its own document: its permission catalog, its permission policies
and the role bindings that grant them. The app comes from the host and the credential, never from the client: a service can be bound to one app
(`cosec.authorization.scope.app-id`), and then serves only credentials issued for it; a service without an app decides
in the app its credential names, and a credential without one reads no app document
([Apps](../integration/authentication.md#apps)).

### Space

An optional partition of an app's roles, such as a team or a project, in which a principal can hold different roles. CoSec keeps no registry of spaces: a space is an opaque id, and several apps may use the same id. A request that
names no space is in the **default space**. A role claimed as `clerk` applies in the default space; `clerk@team-a`
applies in `team-a` only. A request can name a space other than the default only when the principal has a role there.

**The default space has two spellings.** In documents, claims, `scope.space` and the audit record it is the empty string `""`. In the control plane's resource names it is `(default)`, as in `scope/<tenant>/<app>/(default)`. `(default)` is not a space a request can select, so a claim `admin@(default)` is read but applies nowhere. An empty app is likewise "no app": it is absent in conditions, but `""` in the audit record and the forwarded identity.

### Scope

The checked context of one request: its tenant (always the principal's), its app, its space, and the principal's roles
in that space. `ScopeResolver` builds it from the principal, the host's app and the space the request asks for, or rejects the
request; nothing else can construct one. Where these checks draw the line is in
[Trust boundaries](./security-model.md#trust-boundaries).

### Action and resource

What the request does and to what. Without a route map, an HTTP request is the action `http:<METHOD>` (such as
`http:GET`) on the resource `http:path:<path>`. A [route map](../architecture/extending.md#route-maps) can give a
service's routes their own actions and typed resources, such as `order:read` on `order/42`. A request is one or the
other, never both, which decides [which statements apply](../scenarios/route-maps.md#a-request-has-one-action).

## Documents and policies

### Document

A versioned JSON file that holds policies and bindings. There are three kinds:

| Kind | One per | Holds |
|---|---|---|
| Platform document | deployment | Policies shared by every tenant, bindings to anyone or to principals, platform boundaries, and the condition limits and attribute catalog every document compiles against. |
| App document | app | The app's permission catalog, its permission policies (`perm:<app>/<permission>`) and the role bindings that grant them. |
| Tenant document | tenant | The tenant's own policies, its bindings to anyone, to principals and to roles, and the platform boundary that holds the tenant, if not the default one. |

Every kind of document can also carry rate limit rules. App and tenant documents may refer to platform policies, never
the other way round. Every field is in the [Policy language](../policies/language.md).

### Policy

A named set of statements, with an optional condition that every statement also needs. An **attachable** policy can be
attached to a principal directly. A policy has no effect until a binding or an attachment applies it.

### Statement

One rule in a policy:

- an **effect**: `allow` (grants what matches) or `deny` (refuses it, and wins over every ALLOW);
- one or more **actions**, such as `http:GET`, `order:read`, `order:*` or `*`;
- **resources**, such as `http:path:/orders/{id}` or `order/**` (none means any resource);
- an optional **condition**.

A statement matches when an action, a resource and the condition all match. Variables a resource pattern captures are
visible to that statement's condition only.

### Condition

A test over attributes of the request: the subject (`subject.id`, `subject.authenticated`), the scope (`scope.space`,
`scope.roles`), the action and resource, the environment (`environment.ip`, `environment.time`), and external attributes
(`ext.<provider>.<name>`) that attribute providers fetch. Operators such as `eq`, `in`, `prefix`, `cidr`, `time` and
`exists` combine with `and`, `or` and `not`. The full list is in the [Policy language](../policies/language.md).

## Bindings and memberships

### Binding

"This subject is subject to this policy, in this app and space." A binding names a subject (anyone, a principal of a
tenant, or a role), an app and a space (each exact or `*` for any), a policy, and optionally an expiry. It has no effect
of its own: the policy's statements decide. An expired binding does not apply.

### Attachment

A policy a principal lists directly, in its token's `policies` claim or from the membership provider. Only attachable
policies of the platform or of the principal's tenant can be attached.

### Membership

What a principal is a member of: its roles in each space and its attachments. Memberships come from the token's claims,
or, when the token carries the provider marker, from a `MembershipProvider`. They are never part of a document. When they
cannot be read, the request fails closed; it is never treated as having no roles.

### Role

A named set of permissions, held by a member in a space. A **system role** is defined in an app's document and applies in
every tenant; a **custom role** is defined in a tenant's document and applies in that tenant. A role grants through
bindings, which CoSec expands from the role's permissions ([Role permissions](../policies/role-permissions.md#roles)).
The token's `roles` claim lists every role the member holds as `role` or `role@space`; the `roles` of the forwarded
identity, of the audit record and of `scope.roles` are only the roles in the request's space.

### Permission

An entry of an app's catalog that writes an `action` (and optionally a `resource`). It is the policy
`perm:<app>/<id>`, and a role holds it by id ([Role permissions](../policies/role-permissions.md#short-form-permissions)).

### Catalog

An app's permission catalog: groups of permissions with ids, names and descriptions, for user interfaces, generators
and validation. It takes no part in evaluation; the permission policies and role bindings do. Writing catalogs and
granting permissions to roles is in [Role permissions](../policies/role-permissions.md).

## Decisions

The engine answers every request it evaluates with one of three decisions:

| Decision | When |
|---|---|
| `Permit` | An ALLOW statement matched, no DENY did, nothing that could hide a DENY was left unchecked, and the tenant's platform boundary, if any, allows the request too. It may carry obligations. |
| `Deny` | A DENY statement matched, the scope was rejected, or nothing matched (the implicit deny). |
| `Indeterminate` | No DENY matched, but something that could hide one could not be checked: a document, a policy, a membership or an attribute that could not be read, or a DENY that failed. Callers deny. |

The rules behind them are in the [security model](./security-model.md). How each becomes an HTTP response (`401` for an
anonymous caller and `403` for a signed-in one when denied, `503` when indeterminate, and so on) is in
[Web integration](../integration/web.md).

### Platform boundary

The ceiling of what a tenant can be permitted, set by the platform: a policy with `boundary: true` that a Permit must
also match. The platform document names the default one; a tenant document may name another
([Platform boundary](./security-model.md#platform-boundary)).

### Obligation

Something the pipeline must do before a permitted request proceeds. Today the only obligation is a **rate limit**: spend
one permit from the rule's bucket. A denied request never spends one. An exhausted rule turns the permit into a `429`;
see [Rate limiting](../policies/rate-limiting.md).

## Snapshots and the control plane

### Snapshot

The compiled, immutable documents an instance holds in memory. One evaluation reads one revision of the platform
document, of its tenant's document and, when needed, of its app's document. Redis is the store of record; instances
revalidate their copies. See [Storage](../operations/storage.md).

### Revision

One published version of a document, numbered from 1 and increasing by one per publish. Each revision is recorded with
its parent, so history, diff and rollback read the chain. The **head** is the revision instances serve.

### Control plane

The only writer of documents (with `cosec-migrate`, which writes through the same store). It authorizes every operation
as the operator, checks and compiles every change before it is published, and moves a head only from the revision the
change was based on. Its data-plane counterpart, the request pipeline, only reads.

### Change and shadow window

A **change** (change request) is a set of edits to one document, based on one revision: proposed, checked, optionally
shadowed and approved, then published. A **shadow window** stages the change's candidate so that running instances
evaluate a sample of live requests against it too and report where the outcomes would differ, before anything is
enforced. How to use them is in [Testing policies](../policies/testing.md).

### Break-glass

An emergency binding of a platform principal to a platform policy (by default the built-in `cosec:root`, which allows
everything), with an expiry. It is created only through the control plane, with a second person's approval, and every
request it permits must be written to a REQUIRED audit listener. Its policy is evaluated like any other, so a matching
DENY still wins; it is reported as the reason for a permit only when nothing else permits the request.

**Next:** how these terms relate, as a diagram, in [Model](./model.md).

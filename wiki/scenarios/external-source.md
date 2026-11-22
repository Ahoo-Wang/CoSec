---
title: "Roles From an External Source"
description: "Keep CoSec's roles and permissions in step with an identity system that owns them: what must exist first, the sync service's principal, the typed calls each change becomes, what publishes alone and what waits for a person, and how conflicts, replays and publishing behave."
---

# Roles From an External Source

For a team whose roles and permissions are owned by another system, such as an event-sourced identity system that
issues the tokens, and that wants CoSec to enforce what that system says. CoSec's documents are then a **projection**
of the source: a sync service writes them, through the control plane only. This page shows what must exist first, the
principal the sync service acts as, the calls each event becomes, and what publishes alone and what waits for a person.

## What you need

| | External source |
|---|---|
| Source of truth | Your identity system: roles, the permissions of a role, and the app's permission list |
| A sync service | A consumer of the source's events that calls the control plane through `ControlClient` (`cosec-control-sdk`) |
| Its principal | `sync-service` of the `(platform)` tenant, bound by the platform document to the [role class](../policies/testing.md#the-role-class) on tenants' documents (below). It is not the trust root |
| People | Platform administrators who approve the app's catalog on the operators' routes |
| Documents | The tenant documents hold `roles`; the app document holds the `catalog` |
| Tokens | The identity system issues them, with its role ids in `roles` |

**The sync service calls `ControlService`, never Redis and never `ControlPlane`.** A write to the cache skips
validation, compilation and the audit record; whatever bypasses the control plane is not checked before it is
enforced. `ControlPlane` is `@InternalCoSecApi`. `ControlService` is the control plane as a service calls it, in the
same process or over the network, with the same answers either way
([Changing policies from your code](../integration/checks.md#changing-policies-from-your-code)).

## What publishes alone, and what waits

Every change the sync service makes is analyzed: through `ControlService` no one is the trust root, whoever the caller
states, a platform administrator included (`CTL-81`). What it can do alone is bounded by the role class:

| The source changes | The change | What happens |
|---|---|---|
| A role is created or its permissions change | `putRole` on the tenant's document | publishes alone, by the role class, and records a `ROLE_CLASS` escalation that names the holder |
| A role is deleted | `removeRole` | publishes alone: a removal grants nothing |
| An app's permissions | `putCatalog` on the app's document | waits for a person (`AWAITING_APPROVAL`, `NOT_ANALYZED`): a catalog is what every tenant's roles may hold |
| A role of the control plane's own app (`cosec.control.app-id`), or one with no app | `putRole` | waits for a person: who governs a tenant's policies is for a person to appoint |

The role class is `cosec:manage-roles` on a tenant's document: a change that only puts or removes roles of a business
app needs no second person on account of who grants. What such a role can grant is bounded by the format, not by the
sync service: it binds only the tenant's own members, in its app, to the permission policies that app's catalog
defines, which people approved; the tenant's boundary caps it
([The role class](../policies/testing.md#the-role-class)).

## What must exist first

In this order, once per deployment:

1. **The control plane, reachable from the sync service.** Either the sync service's host runs it (`cosec-control` and
   `cosec-cocache` on the classpath, Redis at `maxmemory-policy noeviction`,
   [Quick start](../guide/quick-start.md#prerequisites)), or a [control-plane host](../operations/deployment.md#the-control-plane-host)
   does and the sync service names its service port in `cosec.control.service-url` (with `cosec-control-http`). The
   starter provides one `ControlService` either way (`STA-11`); `ControlClient(service)` writes and reads it in typed
   values.
2. **The first platform document**, from `cosec-migrate publish`. It binds `sync-service` to the role class on tenants'
   documents, lets it propose the app's catalog, and lets authenticated people reach `/cosec/control/v1/**` behind the
   gateway, to approve:

   <<< @/examples/external-source/documents/platform.json [documents/platform.json]

   `cosec:grant` on the scopes is what a role's bindings need: each role grants its members its app's permissions. The
   control plane asks for it on each binding a role derives, as for anyone.
3. **The people who approve**: `cosec-migrate bootstrap --to-redis redis://localhost:6379 --platform-admin <person>`
   binds a person's principal to `cosec:platform-admin`. They approve on the operators' routes, with a token the
   gateway verified.
4. **The app document with the catalog, before any tenant role that lists its permissions.** A role that names a
   permission the catalog does not define does not publish (`REFERENCES`). The catalog is the sync service's proposal
   and a person's approval, or a person's own change.

## What the sync produces

A tenant document that is a projection of the source holds one role entry per role of the source, named by the
source's id:

<<< @/examples/external-source/documents/tenants/acme.json [documents/tenants/acme.json]

Names and descriptions may be left out when the source owns them; `id`, `app` and `permissions` are what CoSec needs.
The app document's catalog lists the permissions the source knows, as short-form entries
([Role permissions](../policies/role-permissions.md#short-form-permissions)).

## The sync service in Kotlin

Each event becomes one call of `ControlClient.proposeAndPublishWithoutShadow`, as `sync-service`: it proposes on the
document's head, skips the shadow window, and publishes, or answers the change waiting for a person:

<<< @/examples/external-source/kotlin/RoleSync.kt [RoleSync.kt]

Wire it over the starter's `ControlService`:

```kotlin
@Bean
fun roleSync(service: ControlService): RoleSync = RoleSync(ControlClient(service))
```

- **Who acts.** `ControlCaller.service("sync-service")` states the service's own principal in the platform tenant: in
  process, the control plane believes code in its own process; over the network, the starter's `HttpControlService`
  writes it in `CoSec-Identity`, and the control-plane host believes it because only the services you name reach its
  service port. What is believed is who acts, never what that principal may do: the policies decide each call, and the
  record names `sync-service`.
- **What `proposeAndPublishWithoutShadow` does about conflicts.** A head that moved is read again and the change
  proposed again; a staging slot another change holds is waited out within the patience it is given, and replicas of
  the sync service wait for each other the same way. A replica that died leaves its change holding the slot; the next
  write of the same principal takes it over after one patience. A change the control plane refuses (a finding such as
  `REFERENCES`) is answered `ABANDONED` with its findings, nothing left open: that is the projection's bug to fix, not
  to retry.
- **A dry run.** `check` answers what a proposal would (its state, findings and escalations) and stores nothing, takes
  no slot and writes no change event (`CTL-78`): `wouldWrite` above tells whether a role would publish alone before the
  sync service writes it.
- **The catalog.** `catalogChanged` answers a change in `AWAITING_APPROVAL`: report its id to a person, who approves it
  on the operators' routes ([Approval](../policies/testing.md#approval)). Once approved, either of them publishes it
  (`ControlClient.publish`); a publish is analyzed again, and the approval covers what was approved.

The sample is compiled with the tests and run against a control plane over this page's documents
(`ExternalSourceExampleTest`): a role written publishes with `ROLE_CLASS`, a role deleted publishes alone, the catalog
waits for a person, and the dry run stores nothing. The `SourceRole` type, the events and the wiring are yours.

## The same changes as the control API's bodies

`ControlClient` writes each call as the control API's route takes it. For a client in another language, these are the
bodies, the first change to a document based on revision `0` and every later one on the revision last read:

<<< @/examples/external-source/changes/first-catalog.json [first-catalog.json]

<<< @/examples/external-source/changes/first-role.json [first-role.json]

<<< @/examples/external-source/changes/role-changed.json [role-changed.json]

<<< @/examples/external-source/changes/role-deleted.json [role-deleted.json]

<<< @/examples/external-source/changes/catalog-changed.json [catalog-changed.json]

Each goes `POST /changes`, `POST /changes/{id}:skip-shadow` and `POST /changes/{id}:publish` on the control-plane
host's service port, with the caller stated in `CoSec-Identity` as `IdentityHeader.write` writes it. To reconcile, send
the whole document the source implies to `POST /documents/tenant:acme:apply` with `{"document": …, "mode": "REPLACE",
"note": …}` (`ControlClient.apply`): it proposes the operations the difference amounts to, and is analyzed as they are.

## How it knows a change is published

`proposeAndPublishWithoutShadow` answers the change `PUBLISHED`, with its `publishedRevision`, once the revision is the
document's head. Other instances of the data plane install it within
`cosec.authorization.snapshot.revalidate-interval` (10 seconds by default,
[Storage](../operations/storage.md#how-instances-read-documents)). With instances that report their status,
`GET /documents/{key}/revisions/{revision}/propagation` on the operators' routes counts those that have installed it
([Storage](../operations/storage.md#revisions-history-and-rollback)). A token already issued applies its roles when the
user next logs in, whatever the document says (below).

## How it behaves

- **Who may grant.** The source decides which of its users hold which role. The sync service writes the roles alone,
  under the role class; what a role may hold, the catalog, is approved by people. Every change is recorded in the audit
  trail as `sync-service`'s.
- **Stating an administrator changes nothing.** A sync service that states a platform administrator through
  `ControlService` is analyzed as any caller (`CTL-81`): its free-form grants wait for a person. Give it the role class
  instead.
- **Replays are safe.** `putRole` replaces a role whole, so applying an event twice leaves the same document.
- **One writer per document.** A change takes the document's staging slot until it publishes. Do not let people and the
  sync service change the same document: people keep the tenant document of their custom roles, the source keeps the
  others.
- **Ordering.** Process the source's events for one document in order. Different documents can be synced in parallel.
- **Tokens decide when it applies.** A role in a token takes effect when the user next logs in, whatever the document
  says. For a change that must apply at once, serve memberships from a `MembershipProvider` or revoke the principal's
  tokens ([Memberships](../integration/authentication.md#memberships)).

## If it fails

- `ControlRefusedException` with `403`: `sync-service` lacks an action the call needs (`cosec:write` on the role,
  `cosec:grant` on a binding it derives, `cosec:skip-shadow`). With `404`: the call is outside what `ControlService`
  reaches, such as the platform document.
- A role change answered `AWAITING_APPROVAL` with `CROSS_TENANT`: the principal does not hold `cosec:manage-roles` on
  the tenant's document. With no `ROLE_CLASS` and a role of the control plane's own app: that role is never the class's.
- A change answered `ABANDONED` with a `REFERENCES` finding: a role lists a permission the app's catalog does not
  define. Publish the catalog change first.
- A permission the app removes while a tenant role lists it: the catalog change does not publish until the roles stop
  listing it ([Role permissions](../policies/role-permissions.md#roles)).

**Next:** what a route map changes about the statements that apply, in [Authorizing business actions](./route-maps.md).

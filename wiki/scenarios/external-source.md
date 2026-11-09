---
title: "Roles From an External Source"
description: "Keep CoSec's roles and permissions in step with an identity system that owns them: which control-plane call each change becomes, who runs it, and how conflicts and replays behave."
---

# Roles From an External Source

For a team whose roles and permissions are owned by another system, such as an event-sourced identity system that
issues the tokens, and that wants CoSec to enforce what that system says. CoSec's documents are then a **projection**
of the source: they are written by a sync service, and only through the control plane. This page shows the calls, the
principal that runs them and what to expect from conflicts.

## What you need

| | External source |
|---|---|
| Source of truth | Your identity system: roles, the permissions of a role, and the app's permission list |
| A sync service | A consumer of the source's events that calls the control API |
| Its principal | A principal of the `(platform)` tenant holding `cosec:approve`, `cosec:skip-shadow` and `cosec:publish`: the trust root (`cosec:platform-admin` does) |
| Documents | The tenant documents hold `roles`; the app document holds the `catalog` |
| Tokens | The identity system issues them, with its role ids in `roles` |

Do not write Redis directly. A write to the cache skips validation, compilation and the audit record; whatever
bypasses the control plane is not checked before it is enforced. The control plane is the only writer.

## What the sync produces

A tenant document that is a projection of the source holds one role entry per role of the source, named by the
source's id:

<<< @/examples/external-source/documents/tenants/acme.json [documents/tenants/acme.json]

Names and descriptions may be left out when the source owns them; `id`, `app` and `permissions` are what CoSec needs.
The app document's catalog lists the permissions the source knows, as short-form entries
([Role permissions](../policies/role-permissions.md#short-form-permissions)).

## Source change to control-plane call

| The source changes | Control-plane call |
|---|---|
| A role's permissions | `putRole`, without `ifMatch` |
| A role is deleted | `removeRole` |
| An app's permissions | `putCatalog` |
| Periodic reconciliation | `apply` with `MERGE`, or `REPLACE` when the source is the only writer of the document |

<<< @/examples/external-source/changes/role-changed.json [role-changed.json]

<<< @/examples/external-source/changes/role-deleted.json [role-deleted.json]

<<< @/examples/external-source/changes/catalog-changed.json [catalog-changed.json]

`base` is the revision the sync service last read for that document; `note` is for the audit trail, so name the source's
event there. Each change goes the same three steps, which a sync service repeats for every event:

```bash
ID=$(curl -s -X POST "$CONTROL/changes" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d @role-changed.json | jq -r .id)
curl -s -X POST "$CONTROL/changes/$ID:skip-shadow" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"projection of event 5521"}'
curl -s -X POST "$CONTROL/changes/$ID:publish" -H "Authorization: Bearer $TOKEN"
```

To reconcile, send the whole document the source implies to `POST $CONTROL/documents/tenant:acme:apply` with
`{"document": …, "mode": "MERGE", "note": …}`; `REPLACE` also removes the roles the document leaves out.

## How it behaves

- **Who may grant** is the source's decision. The sync service is the trust root, so CoSec does not second-guess what it
  projects, and records every change in the audit trail. With `cosec.control.approval.four-eyes: true`, what the
  analysis cannot prove contained would wait for a second platform principal: leave it off for an automated projection.
- **Replays are safe.** `putRole` replaces a role whole, so applying an event twice leaves the same document.
- **Conflicts are the projection's to retry.** A change whose `base` is no longer the document's revision is a `409`;
  read the document, apply the pending events again on the new base, and propose again.
- **One writer per document.** A change takes the document's staging slot until it publishes. If someone else holds it,
  `skip-shadow` answers `409`, and only a change that narrows may preempt. Do not let people and the sync service change
  the same document; people keep the tenant document of their custom roles, the source keeps the others.
- **Ordering.** Process the source's events for one document in order. Different documents can be synced in parallel.
- **Tokens decide when it applies.** A role in a token takes effect when the user next logs in, whatever the document
  says. For a change that must apply at once, serve memberships from a `MembershipProvider` or revoke the principal's
  tokens ([Memberships](../integration/authentication.md#memberships)).

## Check it

The scenarios in [`wiki/examples/external-source`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/external-source/scenarios)
run on every build, and the change bodies above are decoded as the control API decodes them. A token holding
`role-01HQ7Z` ships an inbound order; a token holding the deleted `role-01HQ7Y` is granted nothing.

## If it fails

- `403` from the control API: the sync principal lacks one of the three actions, or its token is not issued for the
  control plane's app ([Change a document later](../guide/quick-start.md#change-a-document-later)).
- `422` `REFERENCES` on a tenant document: a role lists a permission the app's catalog does not define. Publish the
  catalog change first.
- A permission the app removes while a tenant role lists it: the publish is refused until the roles stop listing it
  ([Role permissions](../policies/role-permissions.md#roles)).

**Next:** what a route map changes about the statements that apply, in [Authorizing business actions](./route-maps.md).

---
title: "An App Without Spaces"
description: "A service that is one app, with a permission catalog, system roles, a route map and tokens bound to the app, end to end and without spaces."
---

# An App Without Spaces

For a team whose service is one **app** used by many tenants, with permissions that users get through roles. Spaces are
not needed: everything happens in the default space. This page builds the app `wms` from the route map to the token,
with documents and settings that run, and checks them with the test kit. The model is in [Model](../guide/model.md).

## What you need

| | An app without spaces |
|---|---|
| Dependencies | The [Quick start](../guide/quick-start.md#add-the-dependency)'s, and `cosec-control` once documents change at runtime |
| Configuration | `cosec.jwt.secret`; `cosec.authorization.scope.app-id: wms`; `cosec.authorization.routes.location: classpath:cosec-routes.json` |
| Files | `cosec-routes.json` in the service; `platform.json` and `apps/wms.json` published to Redis |
| Concepts | App documents, the catalog, short-form permissions, system roles, route maps, tokens bound to an app |
| Left out | Spaces: no `spaced`, no `role@space`, no `CoSec-Space-Id` |

## Map routes to actions

The service names its operations, so that permissions say `wms:warehouse.edit` and not `PUT /warehouses/**`:

<<< @/examples/app/routes/cosec-routes.json [cosec-routes.json]

A request that matches a route is that route's action on its resource; any other request stays `http:<METHOD>` on its
path. What a route map changes for the statements that apply is in [Authorizing business actions](./route-maps.md).

## Describe the app

The app document holds the catalog, whose entries that write an `action` are the permissions, and the system roles:

<<< @/examples/app/documents/apps/wms.json [documents/apps/wms.json]

- Each entry with an `action` is the permission `perm:wms/<id>`: one ALLOW statement of that action and `resource`. No
  policy is written by hand ([short-form permissions](../policies/role-permissions.md#short-form-permissions)).
- A role lists the permissions it holds. `"*"` is every permission the catalog defines, so the role follows the catalog
  as it grows; the diff and the approval of a catalog change show the new grants.
- The group is not `spaced`, so each permission applies in the default space, the only one this app has.
- The roles apply to every tenant. A tenant that needs its own role adds one: [System and custom roles](./roles.md).

## The platform document

<<< @/examples/app/documents/platform.json [documents/platform.json]

It permits only the login path and the control API's paths. Everything the app allows comes from its own document,
through roles, so nothing here mentions `wms`.

## Configure the service

```yaml
cosec:
  jwt:
    secret: ${COSEC_JWT_SECRET}
  authorization:
    scope:
      app-id: wms
    routes:
      location: classpath:cosec-routes.json
```

`scope.app-id` makes `wms` the host's app: the service serves only tokens issued for `wms`, and reads the `wms`
document for requests that carry a role. It is the app the **service** serves, never something the client says.

## Publish, and issue tokens for the app

Publish the documents as in the [Quick start](../guide/quick-start.md#publish-the-platform-document), with both files in
the report:

```bash
cd documents
{ printf '{"accepted":true,"outputSha256":{'; sep=""
  for f in platform.json apps/wms.json; do
    printf '%s"%s":"%s"' "$sep" "$f" "$(shasum -a 256 "$f" | cut -d' ' -f1)"; sep=","   # Linux: sha256sum
  done; printf '}}\n'; } > report.json
cosec-migrate publish --to-redis redis://localhost:6379 --in .
```

The login says which app the token is for, and the roles the user holds in it:

```kotlin
TokenSubject(id = user.id, app = "wms", tenant = user.tenant, roles = setOf("clerk"))
```

A token issued for another app, or for none, is refused by this service
([Apps](../integration/authentication.md#apps)).

## Check it

The scenarios in [`wiki/examples/app`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/app/scenarios) run
against these documents on every build:

| Request | Token | Result |
|---|---|---|
| `GET /warehouses/7` | role `clerk` | `200`: `warehouse.read` |
| `PUT /warehouses/7` | role `clerk` | `403`: the clerk does not hold `warehouse.edit` |
| `POST /inbound/42/ship` | role `manager` | `200` |
| `PUT /warehouses/7` | role `admin` | `200`: `"*"` |
| `GET /warehouses/7` | no role | `403` |
| `GET /warehouses/7`, `CoSec-Space-Id: wh-01` | role `clerk` | `403` `scope-rejected`: a space the token does not list |
| `GET /warehouses/7` | role `clerk`, issued for `pos` | `403` `scope-rejected` |

Test your own documents the same way, with `cosec-testkit` ([Testing decisions](../policies/testing.md#testing-decisions)).

## Change it later

A new permission is a catalog entry and, usually, a line in a role. Change the app document through the control plane,
with a principal of the `(platform)` tenant, as in [Change a document later](../guide/quick-start.md#change-a-document-later):
`putCatalog` and `putRole` operations, or `POST /documents/app:wms:apply` with the whole document. Nothing is published
before it has been checked, and the change shows what it adds to each role.

## If it fails

- Every request is `503`: no platform document yet, or the app document does not compile
  ([Troubleshooting](../operations/troubleshooting.md#unavailable)).
- `403` `scope-rejected`: the token's app is not `wms`
  ([`scope-rejected`](../operations/troubleshooting.md#scope-rejected)).
- A permitted role is `403`: ask the decision why with
  [Explaining a decision](../integration/checks.md#explaining-a-decision).

**Next:** let tenants add roles, in [System and custom roles](./roles.md).

---
title: "Authorizing Business Actions"
description: "When to name routes with a route map, and what a mapped route changes: the http statements and rate limit rules that stop applying to it, with a checked example and the fix."
---

# Authorizing Business Actions

For a team deciding whether to give its routes business names such as `wms:warehouse.purge`, and for platform authors who
write DENYs and rate limits that must hold for services they do not own. A **route map** turns a request's method and
path into an action and a typed resource. It also changes which statements apply to the request, and that is the part
to know before you add one.

## Do you need a map

`http:<METHOD>` on `http:path:<path>` is a complete authorization language: path patterns capture variables, conditions
read them, and a permission can be written with `"action": "http:GET"` alone. A route map is a layer of names above it.

| Use a route map when | Because |
|---|---|
| Policies must survive URL changes and versions (`/v1`, `/v2`) | The action name does not depend on the path |
| You list permissions in a console and grant them by name | The catalog is organized by action |
| Requests and code-level checks share one vocabulary | `POST /orders/42/cancel` and `checker.check(ctx, "order:cancel", "order/42")` are the same statement |

| You can go without one when | Because |
|---|---|
| The service is behind Spring Cloud Gateway | The gateway loads no route map ([Gateway topologies](./gateway.md)) |
| A small service whose paths are its natural vocabulary | `http:GET` and `http:path:/orders/{id}` are enough |
| You check business actions only in code | `AuthorizationChecker` takes the action as it is |

## Setting one up

A service loads the maps named by `cosec.authorization.routes.location`. Unset, it loads none, unless
`classpath:cosec-routes.json` exists, in which case it fails at startup until the property names that file (or is set
empty on purpose). The startup log lists each map, its service and its number of routes. The file, and generating it
from OpenAPI, are in [Route maps](../integration/web.md#route-maps); a worked service is
[An app without spaces](./app.md#map-routes-to-actions).

## A request has one action

A request is either mapped, and then it is that route's `service:verb` on its typed resource, or unmapped, and then it
is `http:<METHOD>` on its path. Never both. So:

- a statement on `service:verb` never applies to an unmapped request, and
- a statement on `http:*` or `http:GET` never applies to a mapped one.

[Rate limit rules](../policies/rate-limiting.md#rules) match like a DENY, by action and resource, so the same holds for
them: a rule on `http:POST` and `http:path:/auth/login` stops applying to the request once `POST /auth/login` has a route.

This matters most for the platform. A platform DENY for `/admin/**` is written on `http:*`. When a service team adds a
route for an admin path, its requests are no longer `http:` requests, and the DENY no longer matches them. CoSec has
no condition on the request path, so a platform author cannot write "closed whether or not mapped" once; the DENY is
written for both actions.

### An example

A route map gives `DELETE /admin/warehouses/{id}` the action `wms:warehouse.purge`:

<<< @/examples/route-maps/routes/cosec-routes.json [cosec-routes.json]

and the platform closes the admin paths with a DENY on `http:*`, while the `admin` role holds every permission of the
app, `wms:warehouse.purge` included:

<<< @/examples/route-maps/documents/platform.json [documents/platform.json]

The scenarios in [`wiki/examples/route-maps`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/route-maps/scenarios)
show both sides:

| Request | Decision |
|---|---|
| `DELETE /admin/x`, no route for it: `http:DELETE` on `http:path:/admin/x` | Deny: the platform DENY matches |
| `DELETE /admin/warehouses/7`, mapped: `wms:warehouse.purge` on `warehouse/7` | **Permit**: the DENY does not apply, and the role allows |

The fix is a second statement on the action, next to the first:

<<< @/examples/route-maps/documents-fixed/platform.json [documents/platform.json, fixed]

Now the mapped request is denied as well (`scenarios-fixed` in the example). Do the same for a rate limit rule: write it
for the action, or for both.

### A checklist

- Adding a route to a map? Review the platform DENYs and rate limit rules that name its path, and repeat them for its
  action.
- Writing a platform DENY or limit for services you do not own? Ask whether each has a map, and write the statement for
  the `http` form and for the actions that cover the path.
- Seeing a request treated unexpectedly? `explain` and the decision event show the action and resource the request was
  decided as ([Explaining a decision](../integration/checks.md#explaining-a-decision)).

## Generated from OpenAPI

With `cosec-openapi`, springdoc and Spring Boot Actuator, `cosecServiceGenerator/routes` generates the map from the
OpenAPI document springdoc scans, and `cosecServiceGenerator/app` the app document: a catalog of short-form permissions,
one per action, with no policy to write by hand and no roles. The groups are not `spaced`; mark them `spaced` when the
app uses spaces ([Role permissions](../policies/role-permissions.md#generating-a-catalog-from-openapi)). Generating
maps *every* operation, so every generated route leaves the `http` statements above behind.

**Next:** the same question at the edge, in [Gateway topologies](./gateway.md).

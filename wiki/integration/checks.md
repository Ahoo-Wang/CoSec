---
title: "Checks in Code"
description: "Ask CoSec for a decision from your own code, enforce object-level rules, and explain why a decision came out as it did."
---

# Checks in Code

For developers whose rules depend on more than the route: showing or hiding UI actions, checking a loaded object, or
diagnosing a decision. This page covers `AuthorizationChecker`, which asks the engine directly, and the control
plane's `explain`, which says why a decision came out as it did.

## When to check in code

The [web adapter](./web.md) already authorizes every request before your handler runs. Check in code only when you
need an answer the adapter cannot give:

- **What may the user do next?** A page decides which buttons to show, without sending those requests.
- **Is this object theirs?** The answer depends on data you load in the handler, such as the order's owner
  ([Object-level rules](#object-level-rules)).

Do not repeat the request's own check in the handler: the adapter has decided it already, with auditing and rate
limiting, and a second check can only disagree with it. A check is advice for the next step, not enforcement of the
current one.

## Checking a permission

The starter registers an `AuthorizationChecker` bean (`me.ahoo.cosec.runtime`). It decides for the principal and
scope of a request the pipeline permitted, so it needs that request's `SecurityContext`
([Accessing the security context](./web.md#accessing-the-security-context)):

```kotlin
suspend fun check(security: SecurityContext, action: String, resource: String): Boolean
```

- `action` is `service:verb`, as the route map names it (`order:cancel`), or `http:<METHOD>` for an unmapped route.
  A string without `:` is `false`.
- `resource` is a typed resource, `type/id` (`order/42`), or a path starting with `/` (`/orders/42`), which is an
  `http` resource.

A service behind the gateway in `cosec.pipeline.mode=gateway` has no checker: it holds no policies to decide with
([Services behind the gateway](./web.md#services-behind-the-gateway)). Check there through the gateway's decision, or
run the service in `enforce` mode.

Two adapter modules add non-suspending forms:

| Module | Function | Use it |
|---|---|---|
| `cosec-webflux` | `AuthorizationChecker.checkMono(security, action, resource)`: `Mono<Boolean>` | In WebFlux and gateway code. It starts on the caller's thread and continues off any client library's I/O thread. |
| `cosec-webmvc` | `AuthorizationChecker.checkBlocking(security, action, resource)`: `Boolean` | On a servlet request thread. It throws on a non-blocking thread, such as a Netty event loop: use `checkMono` there. |

```kotlin
@RestController
class OrderActionsController(private val checker: AuthorizationChecker) {
    // WebFlux: which actions may the caller take on this order?
    @GetMapping("/orders/{id}/actions")
    fun actions(@PathVariable id: String, exchange: ServerWebExchange): Mono<Map<String, Boolean>> {
        val security = exchange.getSecurityContext()!!
        return Mono.zip(
            checker.checkMono(security, "order:cancel", "order/$id"),
            checker.checkMono(security, "order:delete", "order/$id"),
        ).map { mapOf("cancel" to it.t1, "delete" to it.t2) }
    }
}
```

The endpoint itself is a route like any other: your documents must permit it.

### How a check relates to the request pipeline

A check evaluates with the same engine, the same documents and the same rules as a request, in the
[evaluation order](../guide/security-model.md#evaluation-order). It reuses the request's principal, memberships,
scope (tenant, app, space and roles) and environment (client IP, device), and differs only in what surrounds the
decision:

| | Request through the adapter | `AuthorizationChecker` |
|---|---|---|
| Action and resource | from the route map | the ones you pass |
| Snapshot documents | read for the request | read again for the check |
| Decision listeners (audit, metrics, traces) | yes | none |
| Rate limits | spends permits, can answer `429` | never spends, never limited |
| A Permit only break-glass gives | proceeds, after a REQUIRED audit | `false`: a check writes no audit |
| Deny, Indeterminate, an error | a status code | `false`, with a warning in the log for an error |
| Result | an HTTP status | `true` only on a Permit |

A check fails closed: a `SecurityContext` the pipeline did not build (one you made yourself, or none) is `false`,
whatever the documents say. There is therefore no check outside a request: a message consumer that acts for a user
has no permitted request to check as, and must carry its own authorization.

## Object-level rules

Whether a user may change *this* order depends on the order: its owner, tenant or status. The adapter runs before
your handler has loaded anything, so an object-level rule is decided in two steps:

1. **In the adapter**, the route is authorized on what the request carries, such as `order:cancel` on `order/42`.
2. **In your code**, after loading the object, a check names a resource whose id carries the facts the rule needs.

A check passes no resource attributes, only a type and an id. So write the facts into the id, and let a statement
capture them. For example, check ownership as the resource `order-of/<owner>/<orderId>`, with this policy bound to
anyone (`"subject": "*"`):

```json
{
  "id": "own-orders-only",
  "statements": [
    { "name": "not-the-owner", "effect": "deny", "action": "order:cancel", "resource": "order-of/{owner}/*",
      "condition": { "not": { "eq": ["resource.var.owner", { "attr": "subject.id" }] } } }
  ]
}
```

```kotlin
val order = orderRepository.findById(id)
val allowed = checker.checkBlocking(security, "order:cancel", "order-of/${order.ownerId}/$id")
if (!allowed) throw AccessDeniedException()
```

The DENY applies only to `order-of/...` resources, which no route produces, so the route itself is still decided by
your other policies. The check is a separate decision: it is permitted only when an ALLOW also covers
`order:cancel` on `order-of/...`, and it is denied for anyone but the owner. A captured value holds one segment and
is compared exactly; ids are split on `/` and never decoded, so an owner id must not contain `/`. Resource patterns
and conditions are described in [Policy language](../policies/language.md).

The check must run in your code; a handler that skips it is not protected by the rule.

## Explaining a decision

To see why a principal gets a decision for a request, without sending the real request, ask the control plane's
`explain`. It needs `cosec-control` and `cosec-cocache`, and is served on the host as
`POST <base-path>/explain` (default `/cosec/control/v1/explain`; see
[Control-plane routes](./web.md#control-plane-routes)).

It decides with the same scope resolution, document compiler and engine as enforcement, at full trace, on the
published documents: their heads, or the revisions you name. Nothing is spent or changed.

```bash
curl -X POST localhost:8080/cosec/control/v1/explain \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{
  "request": {
    "tenant": "acme",
    "principal": "alice",
    "roles": ["clerk", "lead@team-a"],
    "policies": ["orders-readonly"],
    "app": "order",
    "space": "team-a",
    "action": "order:cancel",
    "resource": "order/42"
  }
}'
```

**Input.** `request` describes the request; nothing is read from a credential:

| Field | Meaning |
|---|---|
| `tenant` | Required. The principal's tenant. |
| `principal` | The principal id; leave it out for the anonymous principal. |
| `roles`, `policies` | The membership claims, written as a token carries them (`role`, `role@space`; [Roles and spaces](./authentication.md#roles-and-spaces)). |
| `app`, `space` | The declared app and space, as `CoSec-App-Id` and `CoSec-Space-Id`; no host app applies. |
| `action` | Required. `service:verb`. |
| `resource` | Required. `type/id`, or a path starting with `/`. |
| `remoteIp` | The client IP. |
| `time` | The request time, an ISO-8601 instant; now by default. |

An optional `at` (`{"platform": 12, "app": 3, "tenant": 7}`) explains on those retained revisions instead of the
heads; a revision no longer kept is `410`. Unknown fields are `400`.

**Output.**

```json
{
  "version": { "platform": 12, "app": 3, "tenant": 7 },
  "decision": {
    "decision": "Deny",
    "by": { "policy": "no-cancel", "statement": 0, "name": "no-cancel", "via": "no-cancel", "document": "platform" },
    "trace": {
      "redacted": 1,
      "candidates": [
        { "effect": "DENY", "outcome": "MATCHED",
          "statement": { "policy": "no-cancel", "statement": 0, "name": "no-cancel", "via": "no-cancel", "document": "platform" } }
      ]
    }
  }
}
```

| Field | Meaning |
|---|---|
| `version` | The revisions of the documents decided on. |
| `decision.decision` | `Permit`, `Deny`, `Indeterminate`, or `Rejected` when the scope is rejected (then `cause` is `APP_MISMATCH` or `SPACE_NOT_MEMBER`, and there is no trace). |
| `decision.by` | The statement that decided: policy, statement index and name, the binding it applied through (`via`: a binding id, or `attached:<policy>`) and its document. Absent for the implicit deny and for Indeterminate. |
| `decision.cause` | For Indeterminate, the type of the cause (such as `MissingPolicies`, `MatcherFailed`, `SourceUnavailable`); never its message. |
| `decision.redacted` | `true` when a statement decided that you may not see. |
| `trace.candidates` | Each statement evaluated: its effect, its statement, and its outcome: `MATCHED`, `ACTION_MISS`, `RESOURCE_MISS`, `CONDITION_MISS`, `GUARD_MISS` (its policy's condition did not hold), `GUARD_ERROR`, `ERROR` or `NOT_EVALUATED`. |
| `trace.redacted` | How many candidates were left out because you may not see them. |

**Who may explain, and what they see.** `explain` is authorized like every control-plane call, as the principal of
the request, in the app `cosec.control.app-id`:

- the caller needs `cosec:explain` on the simulated tenant's document (`doc/tenant/<tenant>`). A tenant's principal
  can only explain requests of its own tenant: tenant isolation comes first and is not a policy. The built-in
  `cosec:tenant-viewer` and `cosec:tenant-admin` grant it on the caller's own tenant, `cosec:platform-admin` on every
  tenant ([Built-in policies](../guide/security-model.md#built-in-policies));
- statements are shown only from documents the caller may read (`cosec:read` on the document). A statement reached
  through an attachment is shown only when the caller may read both the tenant and the platform document. Everything
  else is left out of the trace and counted in `redacted`, and a decision made by a hidden statement says only
  `"redacted": true`;
- without the permission the answer is `403`, and when it cannot be decided `503`, with no detail.

**What it does not see.** An explanation reads the published documents, not what an instance has installed (an
instance may still serve an older revision for a while; see [Storage](../operations/storage.md)). It fetches no external attributes, so a
statement that reads one counts as missing them ([Missing external attributes](../guide/security-model.md#missing-external-attributes)),
and it has no token attributes, blacklist or rate limits.

**Simulating a change.** `POST <base-path>/simulate` takes the same `request`, and optionally a staged change
(`"candidate": "<change id>"`): it answers the decision on the current documents (`enforced`), on the documents with
the change applied (`candidate`), and whether they differ. It needs `cosec:simulate` on the tenant's document and on
the change's. Staging changes, shadow windows and access analysis (`/analyze`) are in
[Testing policies](../policies/testing.md).

**Next:** every property and its default, in [Configuration](../operations/configuration.md); or, to write the rules themselves, [Policy language](../policies/language.md).

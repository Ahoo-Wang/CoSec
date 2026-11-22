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

A check is for **the request in progress**, with the `SecurityContext` of that request. It is not for a message
consumer, a scheduler, or any work that runs after the request: see
[Work that is not the request](#work-that-is-not-the-request).

## Checking a permission

The starter registers an `AuthorizationChecker` bean (`me.ahoo.cosec.runtime`). It decides for the principal and
scope of a request the pipeline permitted, so it needs that request's `SecurityContext`
([Accessing the security context](./web.md#accessing-the-security-context); in a test, one the kit made:
[Testing your application](../policies/testing.md#testing-your-application)):

```kotlin
suspend fun check(security: SecurityContext, action: String, resource: String): Boolean
```

- `action` is `service:verb`, as the route map names it (`order:cancel`), or `http:<METHOD>` for an unmapped route.
  A check applies no route map, so an `http:` action decides as it says, whether or not a route map maps that path
  ([A check and a mapped route](#a-check-and-a-mapped-route)). A string without `:` is `false`.
- `resource` is a typed resource, `type/id` (`order/42`), or a path starting with `/` (`/orders/42`), which is an
  `http` resource. It is read as the pipeline reads a request: its path parameters are removed, a path the pipeline
  [refuses with `400`](./web.md#the-request-path) is `false`, and so is any resource over a request path's length
  bounds. So a check never permits a path no request could be permitted for, even one built from client input.

A service behind the gateway in `cosec.pipeline.mode=gateway` has no checker: it holds no policies to decide with
([Services behind the gateway](./web.md#services-behind-the-gateway)). Check there through the gateway's decision, or
run the service in `enforce` mode.

Two adapter modules add non-suspending forms:

| Module | Function | Use it |
|---|---|---|
| `cosec-webflux` | `AuthorizationChecker.checkMono(security, action, resource)`: `Mono<Boolean>` | In WebFlux and gateway code. It starts on the caller's thread and continues off any client library's I/O thread. |
| `cosec-webmvc` | `AuthorizationChecker.checkBlocking(security, action, resource)`: `Boolean` | On a servlet request thread. It throws on a non-blocking thread, such as a Netty event loop: use `checkMono` there. |

One sample per stack, each with its imports. Each asks which actions the caller may take on an order:

```kotlin
// WebFlux, returning a Mono
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.webflux.ServerWebExchanges.requireSecurityContext
import me.ahoo.cosec.webflux.checkMono

@RestController
class OrderActionsController(private val checker: AuthorizationChecker) {
    @GetMapping("/orders/{id}/actions")
    fun actions(@PathVariable id: String, exchange: ServerWebExchange): Mono<Map<String, Boolean>> {
        val security = exchange.requireSecurityContext()
        return Mono.zip(
            checker.checkMono(security, "order:cancel", "order/$id"),
            checker.checkMono(security, "order:delete", "order/$id"),
        ).map { mapOf("cancel" to it.t1, "delete" to it.t2) }
    }
}
```

```kotlin
// WebFlux, a suspend handler: the exchange parameter gives the context, and check suspends
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.webflux.ServerWebExchanges.requireSecurityContext

@RestController
class OrderActionsController(private val checker: AuthorizationChecker) {
    @GetMapping("/orders/{id}/actions")
    suspend fun actions(@PathVariable id: String, exchange: ServerWebExchange): Map<String, Boolean> {
        val security = exchange.requireSecurityContext()
        return mapOf(
            "cancel" to checker.check(security, "order:cancel", "order/$id"),
            "delete" to checker.check(security, "order:delete", "order/$id"),
        )
    }
}
```

```kotlin
// Spring MVC: the SecurityContext parameter, and checkBlocking on the request thread
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.webmvc.checkBlocking

@RestController
class OrderActionsController(private val checker: AuthorizationChecker) {
    @GetMapping("/orders/{id}/actions")
    fun actions(@PathVariable id: String, security: SecurityContext): Map<String, Boolean> = mapOf(
        "cancel" to checker.checkBlocking(security, "order:cancel", "order/$id"),
        "delete" to checker.checkBlocking(security, "order:delete", "order/$id"),
    )
}
```

The endpoint itself is a route like any other: your documents must permit it.

### The decision behind a check

`decide` takes the same arguments and answers the `Decision` (`me.ahoo.cosec.model.decision`) that `check` turns into
a Boolean. `check` is `decide(...) is Decision.Permit`, so the two always agree:

```kotlin
suspend fun decide(security: SecurityContext, action: String, resource: String): Decision
```

| Decision | When | `check` |
|---|---|---|
| `Permit(by)` | An ALLOW matched and no DENY did. `by` is the statement that permitted: `policy`, `index`, `name`, and `via`, how the policy applied. `BindingRef.Bound(id, document)` is a binding, in a `PLATFORM`, `APP` or `TENANT` document; `BindingRef.Attached(policy)` is a policy the principal's memberships attach. | `true` |
| `Deny(by)` | A DENY matched (`by` is that statement), the tenant's platform boundary refused the permit (`StatementRef.Boundary`), or nothing matched (`null`). A `SecurityContext` the pipeline did not build, an action without `:`, or a resource the pipeline would refuse is `Deny(null)` too. | `false` |
| `Indeterminate(cause)` | No decision could be made: a document could not be read, a DENY failed, the check itself failed, or only break-glass would permit. A failure of the check is logged as a warning and reported as `CheckFailed(error)`: read `error` for what failed. A document the check could not read is `SourceUnavailable`, as on a request. | `false` |

Use it to record what allowed an action your code takes, such as "permitted by role binding `order-admins` of the app
document". A `Decision` stays in your process: never write it or its cause into a response. The web adapters never
see one either, which is how every status code comes from one place
([Responses and status codes](./web.md#responses-and-status-codes)). For the same reason the adapter modules add no
`Mono` or blocking form of `decide`: call it from a coroutine. The obligations a `Permit` carries, its rate limits,
did not run.

### How a check relates to the request pipeline

A check evaluates with the same engine, the same documents and the same rules as a request, in the
[evaluation order](../guide/security-model.md#evaluation-order). It reuses the request's principal, memberships,
scope (tenant, app, space and roles) and environment (client IP, device), and differs only in what surrounds the
decision:

| | Request through the adapter | `AuthorizationChecker` |
|---|---|---|
| Action and resource | from the route map | the ones you pass, read as a request's: an `http` path normalized, `http:HEAD` as `http:GET`, an `http` verb with a lower-case letter, or a path enforcement refuses, a deny (`PIP-15`, `PIP-18`) |
| Snapshot documents | read for the request | read again for the check |
| External attributes | stage A for every request, stage B as the plan names | fetched again for the check's action and resource: each one it can read, from the provider that declares it (a stage-A provider without the scope, as stage A asks) |
| Decision listeners (audit, metrics, traces) | yes | none |
| Rate limits | spends permits, can answer `429` | never spends, never limited |
| A Permit only break-glass gives | proceeds, after a REQUIRED audit | `false`: a check writes no audit |
| Deny, Indeterminate, an error | a status code | `false`, with a warning in the log for an error |
| Result | an HTTP status | `true` only on a Permit; `decide` answers the decision |

A check fails closed: a `SecurityContext` the pipeline did not build (one you made yourself, or none) is `false`,
whatever the documents say. There is therefore no check outside a request.

A check does not authenticate again. It decides with the principal, the memberships and the scope that were resolved
when the request was permitted, and it reads the documents again. It checks no age and no revocation of the token, and
it sees no change of roles since. So during the request, a check keeps permitting after the user logs out, after
`revokeSubject` or after a role is removed, for as long as the documents permit; the request has been decided already.
When the request ends the check stops (`PIP-26`, below); a transport of your own ends it by closing the `Outcome`.
Do not store a
context: authorize where the request arrives and carry the decision with the work.

### A check and a mapped route

A check applies no route map. `check(security, "http:POST", "/orders/42/cancel")` decides `http:POST` on
`http:path:/orders/42/cancel`, as an unmapped request would be decided. When a route map maps that request, the request
itself is decided as its `service:verb` on its typed resource, and a statement on `http:` never covers it
([Route maps](./web.md#route-maps)). So the check and the request can decide differently for the same path: a
policy that permits `order:cancel` and nothing on `http:` permits the request and denies the check, and an `http:*`
ALLOW permits the check and not the request. To ask what a mapped route would be decided as, check its action and its
typed resource, the ones the route map gives it (`order:cancel` on `order/42`).

## Work that is not the request

A message consumer, a scheduler, a retry, a job a request started: none of them has a request to check as. What to do
instead:

- **Authorize where the request arrives, and carry the decision with the message.** The adapter has decided the request
  before your handler runs; add a check there for what the work will do next (`order:cancel` on `order/42`) while the
  request is in progress. Publish the work only when it permits, and let the consumer act on that message without
  asking CoSec again. The message carries the fact that the action was authorized, never the context: a consumer that
  wants the user's identity reads it from the message's own fields (the principal id, the tenant). The consumer then
  trusts what its own channel guarantees about the message, which CoSec does not.
- **Run the pipeline on a port that does not have a filter, rather than verifying tokens by hand.** A service call on
  an internal port, a WebSocket handshake or a message header that carries a token can be put through the same
  `Pipeline.evaluate` a web adapter calls ([Extending](../architecture/extending.md#pipeline-outside-the-adapters)). If
  you must verify a token yourself, do all of it: [Verifying a token outside the
  pipeline](./authentication.md#verifying-a-token-outside-the-pipeline).
- **Work the system starts itself** (a scheduler, a timer, a system command with no caller) is not decided by CoSec in
  8.0. There is no principal to decide as, and a check without a context is `false`. Decide it in your own code, in a
  way you can read: do not fall back to a default that permits everything because the check says `false`. A decision
  as a principal for work outside a request, with an in-process entry for a named service principal, is planned for 8.1.

A check belongs to its request, and the adapter that bound the request ends it when the request completes (`PIP-26`):

- **While the request is in progress**, a check is answered as the table says, however long the request runs: during
  a streamed or server-sent-events body, in an async servlet request, in code the handler awaits. It decides on the
  principal, memberships and attributes the request began with, also once its token has expired meanwhile: the
  pipeline authenticated the request when it began, and a check is not stricter than the request's own decision.
- **Once the request has ended**, every check of its context is `false` (`decide` answers `Deny(null)`), whatever the
  documents say: a context kept in a field, a cache or a message permits nothing afterwards. WebFlux and the gateway
  end a request when its response has been written, failed, or the client went away, before they pass the completion
  on, so code that sees the request complete sees it ended; Spring MVC when the dispatch returns, or, for an async
  request (`DeferredResult`, `StreamingResponseBody`, a `suspend` handler), when it
  completes, fails or times out. Code on the async request's ASYNC dispatch (message converters, a
  `ResponseBodyAdvice`, interceptors) is still within the request. The error page is not: Spring MVC renders it on an
  ERROR dispatch, after the request has ended, so an error page or error controller reads the context but every check
  of it there is `false`.
- **A request your own transport handed to `Pipeline.evaluate`** ends when the transport closes its `Outcome`
  (`AutoCloseable`): `evaluate(facts).use { … }` around a response that is complete when the block returns, or
  `outcome.close()` from the response's completion callback when it outlives the handler (a streamed body, a WebSocket
  session). Closing it when the handler returns ends the request while such a stream is still being written, and the
  checks it makes are `false`. A transport that never closes it leaves the request in progress for as long as its
  context is held ([A transport of your own](../architecture/extending.md#pipeline-outside-the-adapters)).
- **The test kit's contexts** are in progress until the test ends them, as a host would when its response is complete:
  `PipelineFixture.end(context)`, `PermittedRequest.end(context)`, or closing the `Outcome` of `PipelineFixture.evaluate`.
  A test of code that checks after its response ends the context, then asserts the check is `false`.

## Object-level rules

Whether a user may change *this* order depends on the order: its owner, tenant or status. The adapter runs before
your handler has loaded anything, so an object-level rule is decided in two steps:

1. **In the adapter**, the route is authorized on what the request carries, such as `order:cancel` on `order/42`.
2. **In your code**, after loading the object, a check names a resource whose id carries the facts the rule needs.

A check passes no resource attributes, only a type and an id. So write the facts into the id, one segment each, and
let a statement capture them. Here the resource `order-of/<owner>/<status>/<orderId>` carries the owner and the status
of the order, and the policy below is bound to anyone (`"subject": "*"`):

<<< @/examples/checks/documents/platform.json [documents/platform.json]

```kotlin
// Spring MVC; orderRepository and Order are yours. In WebFlux, use checkMono, or a suspend handler and check.
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.webmvc.checkBlocking
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

@PostMapping("/orders/{id}/cancel")
fun cancel(@PathVariable id: String, security: SecurityContext) {
    val order = orderRepository.findById(id)
    val allowed = checker.checkBlocking(security, "order:cancel", "order-of/${order.ownerId}/${order.status}/$id")
    if (!allowed) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    // ... cancel the order
}
```

The scenarios in [`wiki/examples/checks`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/checks/scenarios)
run against this document on every build, with the [test kit](../policies/testing.md#testing-decisions):

| Check | Caller | Decision |
|---|---|---|
| `order:cancel` on `order-of/alice/pending/42` | `alice` | Permit |
| `order:cancel` on `order-of/alice/pending/42` | `bob` | Deny: `not-the-owner` |
| `order:cancel` on `order-of/alice/shipped/42` | `alice` | Deny: `already-shipped` |

`{owner}` and `{status}` each capture one segment, and a statement reads them as `resource.var.owner` and
`resource.var.status` in its own condition. The DENYs apply only to `order-of/...` resources, which no route produces,
so the route itself is still decided by your other policies. The check is a separate decision: it is permitted only
when an ALLOW also covers `order:cancel` on `order-of/...` (`cancel-an-order`), and it is denied for anyone but the
owner and for a shipped order. A captured value holds one segment and is compared exactly; ids are split on `/` and
never decoded, so a value that may hold a `/` does not belong in the id. Resource patterns and conditions are described
in [Policy language](../policies/language.md).

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
| `app`, `space` | The app the simulated credential declares, on a host of that app, and the space it selects (`CoSec-Space-Id`). |
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
| `decision.decision` | `Permit`, `Deny`, `Indeterminate`, or `Rejected` when the scope is rejected (then `reason` says why, and there is no trace). |
| `decision.by` | The statement that decided: policy, statement index and name, the binding it applied through (`via`: a binding id, or `attached:<policy>`) and its document. Absent for the implicit deny and for Indeterminate. |
| `decision.cause` | For Indeterminate only, the kind of failure: `MatcherFailed`, `MissingPolicies`, `AmbiguousPolicy`, `SourceUnavailable`, `DocumentInvalid`, `NotLoaded`, `BoundaryUnavailable`, `AttributeUnavailable`, `CheckFailed`, `MembershipUnavailable`, `RequiredListenerFailed` or `RateLimiterUnavailable`; never its message (`CTL-61`). |
| `decision.reason` | For Rejected only: `APP_MISMATCH` or `SPACE_NOT_MEMBER`. |
| `decision.redacted` | `true` when a statement decided that you may not see. |
| `trace.candidates` | Each statement evaluated: its effect, its statement, and its outcome: `MATCHED`, `ACTION_MISS`, `RESOURCE_MISS`, `CONDITION_MISS`, `GUARD_MISS` (its policy's condition did not hold), `GUARD_ERROR`, `ERROR` or `NOT_EVALUATED`. |
| `trace.redacted` | How many candidates were left out because you may not see them. |

**Who may explain, and what they see.** `explain` is authorized like every control-plane call, as the principal of
the request, in the app `cosec.control.app-id`:

- the caller needs `cosec:explain` on the simulated tenant's document (`doc/tenant/<tenant>`). A tenant's principal
  can only explain requests of its own tenant: tenant isolation comes first and is not a policy. The built-in
  `cosec:tenant-viewer` and `cosec:tenant-admin` grant it on the caller's own tenant, `cosec:platform-admin` on every
  tenant ([Built-in policies](../guide/security-model.md#built-in-policies));
- a statement is shown only when the caller may read (`cosec:read`) the document that defines its policy, whichever
  document the binding it applied through lives in: a tenant's binding to a platform policy, or to an app's permission
  policy, shows a tenant administrator none of that policy's statements. A statement reached
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

## Changing policies from your code {#changing-policies-from-your-code}

A service that changes policies from its own code, such as an IAM service onboarding a tenant or projecting its roles,
calls the control plane through `ControlService` (`cosec-control-sdk`; `@ExperimentalCoSecApi`), whether it embeds the
control plane or calls a control-plane host, and writes and reads it in typed values with `ControlClient(service)`.
The starter provides one bean of `ControlService` (`STA-11`):

- where the instance runs the control plane, the in-process implementation (`cosec-control` brings `cosec-control-sdk`);
- where `cosec.control.service-url` names a control-plane host's control API (such as
  `http://control:8082/cosec/control/v1`, its service port), `HttpControlService` (`cosec-control-http`, which brings
  `cosec-runtime`), which calls that port;
- both at once fails startup: remove one.

Every call is decided by the policies for the principal its `ControlCaller` states, and recorded as that principal's
step. It goes through the control API's own route: the same call answers the same, records the same, and is refused
the same in process and over the network (`CTL-65`, `CTL-77`). Over the network, the caller is stated in `CoSec-Identity`,
written for each call. A request's principal is written as the gateway forwards it, and the application's own principal
as the identity the in-process implementation admits. The host believes the header because only the services you name
reach its service port. A request's principal whose authenticator named no credential cannot be stated over the network,
and is refused before anything is sent.

Over the network, only a `2xx` is an answer: any other status, a redirect or a proxy's error page included, is a
`ControlRefusedException` with that status and body. A call waits for its answer 30 seconds by default (the `timeout` of
`HttpControlService`) and then throws `HttpTimeoutException`. The outcome of a write that timed out is unknown, so read
the change or the document again before retrying. A change it left staged is taken over by the next
`proposeAndPublishWithoutShadow` of the same principal after one patience. Any other failure of the connection is the
`IOException` the client throws: the one thing the in-process implementation never answers.

The starter's `HttpControlService` uses the JDK's default `HttpClient`, which presents no client certificate. For a host
whose service port asks for one (mutual TLS), declare the application's own `ControlService` bean with its client; the
starter then makes none:

```kotlin
@Bean
fun controlService(sslContext: SSLContext): ControlService = HttpControlService(
    URI("https://control:8082/cosec/control/v1"),
    HttpClient.newBuilder().sslContext(sslContext).build(),
)
```

- **Who acts:**
  - `ControlCaller.of(securityContext)` acts as the user whose request you are serving. It takes the
    `SecurityContext` CoSec made for that request, and refuses one your code assembled.
  - `ControlCaller.service("iam", roles)` acts as your service's own principal in the platform tenant, for work no
    request stands behind. Your code states the principal and its roles, and the control plane believes them, as it
    believes any code in its own process: that code holds the control store's write account anyway. What is believed
    is who acts, never what that principal may do. The policies still decide each call for it, with the bindings the
    platform document gives it, and the record names it.
  - Stating a platform administrator, either way, does not make the call the trust root's (`CTL-81`): through
    `ControlService` every change is analyzed. A service that writes what it already decided gets the authority it
    needs from the policies, such as the [role class](../policies/testing.md#the-role-class) for roles it projects into
    tenants, and what the analysis cannot prove waits for a person.
- **What it reaches:** which tenant and app documents there are (`documents`), reads of a tenant's or an app's
  document (`document`, `revisions`, `revision`, `diff`), and
  the change flow of a tenant's or an app's document (`check`, `propose`, `change`, `changes`, `rebase`, `startShadow`,
  `skipShadow`, `abandon`, `publish`; `apply` and `checkApply` of a tenant's). An automated change can start its
  shadow window, read the change until the window closes, and publish with the summary's acknowledgement, rather than
  skip the window. Every change made through it is analyzed, whoever the caller states: no one is the trust root
  here, not even a platform administrator (`CTL-81`). What grants nothing anew (removing a role, a policy or a
  binding) publishes alone, a holder of the [role class](../policies/testing.md#the-role-class) projects roles into
  tenants alone, and anything else, a catalog for one, waits for a person, who approves on the operators' routes.
  Nothing reaches the platform document, approvals, rollback or break-glass: those stay a person's, on the operators'
  routes.
- **What goes in and out:**
  - Requests are the routes' bodies ([Changes](../policies/testing.md#changes)), and documents are in the
    [document format](../policies/language.md).
  - Answers are the control API's JSON ([What a change answers](../policies/testing.md#what-a-change-answers)),
    as text.
  - A refusal is a `ControlRefusedException` with the route's status and problem.
- **What it reaches, exactly:** the routes of the control host's service port, one definition for both
  (`cosec.control.http.service-port`). A change named by its id is reachable only when it changes a tenant's or an
  app's document, and a listing shows only such changes: one by state leaves the platform document's out, and says
  nothing of them.
- **What is recorded:** each step as the stated principal's, with `via.method: in-process` in its origin
  ([Control-plane change log](../operations/observability.md#control-plane-change-log)).

In typed values, `ControlClient(service)` writes each request and reads each answer as the route takes
and answers it (`CTL-76`), over either implementation of `ControlService`. Documents are `DocumentContent` with
`Policy`, `Binding`, `Role` and `Catalog`; a change is `Operation`s (`PutPolicy`, `PutRole`, ...) in a `ChangeRequest`,
or `ApplyRequest` for `:apply`; an answer is a `Change`, a `DocumentHead` or a `Page`. Conditions and rate limit rules
stay JSON (`Condition.of`, `RateLimitRule.of`): the control plane checks them when the change is proposed. A closed set
(`ChangeState`, `Effect`, `ConflictKind`, ...) keeps a value a later host adds as unknown, with its text; a document
entry with a member this version does not know does not read at all, since applying what was read would drop it.
`ControlRefusedException.conflict` says which conflict a 409 is.

`proposeAndPublishWithoutShadow` is what a service that writes what it already decided does for each write: it
proposes on the document's head, skips the shadow window and publishes. It needs `cosec:skip-shadow` beside
`cosec:write`. A head that moved is read again and the change proposed again; a slot another change holds is waited
out, within the patience it is given; concurrent writers acting as one principal, such as a service's replicas, wait
for each other the same way. A writer that died leaves its change holding the slot (`ControlRefusedException.heldBy`
names it): a change of the same author, left `CHECKED`, `SHADOWING` or `READY` and not updated for at least the patience
(by the control plane's times), is abandoned and the slot taken. A dead writer so holds the document for one patience,
and the next write takes it over; give every writer of one principal the same patience. It answers the published change, or one that waits for a
person; a change the control plane refuses when it is proposed (a finding such as `REFERENCES`) is abandoned at once and
answered `ABANDONED` with its findings; when it gives up it throws the refusal, having abandoned the change it proposed.

<<< @/examples/control-sdk/kotlin/AuditorsProjection.kt [AuditorsProjection.kt]

`check` answers what `propose` would answer for the same request (its state, findings, escalations and base) and stores
nothing, takes no slot and writes no change event (`CTL-78`): call it to know whether a change would publish, wait for a
person or be refused before your service stores something of its own. It is decided as the proposal would be: a caller
who may not propose may not check. A service that projects an identity system's roles, with the role class, a dry run
and what waits, is [Roles from an external source](../scenarios/external-source.md).

The same calls as JSON, the routes' bodies and answers as text, through `ControlService` itself:

<<< @/examples/control-sdk/kotlin/Onboarding.kt [Onboarding.kt]

<<< @/examples/control-sdk/changes/auditors.json [auditors.json]

A change that needs a second person's approval stops at `AWAITING_APPROVAL`, whoever proposed it: report its id to a
person, who approves it on the operators' routes. To call another service behind the gateway as a principal of
your own, `IdentityHeader.write(identity)` (`cosec-document`) writes the `CoSec-Identity` header the gateway would
have forwarded.

**Next:** every property and its default, in [Configuration](../operations/configuration.md); or, to write the rules themselves, [Policy language](../policies/language.md).

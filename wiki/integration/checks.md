---
title: "Checks in Code"
description: "Ask CoSec for a decision from your own code, enforce object-level rules, and explain why a decision came out as it did."
---

# Checks in Code

For developers whose rules depend on more than the URL: showing or hiding UI actions, checking ownership of a loaded
object, or diagnosing a decision. This page covers calling authorization directly instead of relying only on the web
adapter.

## When to check in code

The [web adapter](./web.md) already authorizes every request before your handler runs. Check in code only when you
need an answer the adapter cannot give:

- **What may the user do next?** A page decides which buttons to show, without sending those requests.
- **Is this object theirs?** The rule depends on data you load in the handler, such as the order's owner
  ([Object-level rules](#object-level-rules)).
- **Work that is not an HTTP request**, such as a message consumer acting for a user.

Do not repeat the request's own check in the handler: the adapter has decided it already, with auditing and rate
limiting, and a second check can only disagree with it. And a check is advice, not enforcement: keep enforcing on
the real request.

## Checking a permission

Inject the `AuthorizationChecker` bean (registered whenever authorization is enabled). Describe a check like the
request it stands for, as a `CheckRequest`:

| Field | Default | Meaning |
|---|---|---|
| `path`, `method` | method `GET` | The request the check stands for. |
| `appId`, `spaceId` | those of the current request | The app and space whose [role grants](../policies/role-permissions.md#role-grant-scope) apply. |
| `headers` | none | Headers conditions read as `request.header.<name>`. |
| `attributes` | none | Attributes conditions read as `request.attributes.<name>`. |
| `resource` | none | Attributes of the object the action targets; conditions read them as `resource.<name>`. |

The check also keeps the current request's client IP. Call `check(context, request)` for one answer, or
`checkAll(context, requests)` for a map of them; both return a `Mono`. `check(context, path, method)` is the short
form.

```kotlin
@RestController
class PermissionController(private val checker: AuthorizationChecker) {
    // WebFlux; with Spring MVC, take SecurityContextHolder.requiredContext instead of the exchange
    @PostMapping("/permissions/check")
    fun check(@RequestBody actions: Map<String, CheckRequest>, exchange: ServerWebExchange): Mono<Map<String, Boolean>> =
        checker.checkAll(exchange.getSecurityContext()!!, actions)
}
```

A UI posts `{"edit": {"path": "/orders/42", "method": "PUT"}, "delete": {"path": "/orders/42", "method": "DELETE"}}`
and gets `{"edit": true, "delete": false}`. The endpoint itself needs a policy that allows authenticated callers.

Outside a request, for example in a message consumer, build the context for the user yourself:
`SimpleSecurityContext(principal)`. The check then has no current request to take defaults from: name the app and
space explicitly, and note that the client IP is `127.0.0.1`, so a policy that trusts local addresses trusts the
check too.

### How a check relates to the request pipeline

A check uses the same policies, role grants, blacklist and root rule as a real request, in the same
[evaluation order](../guide/security-model.md#evaluation-order). It differs in what surrounds the decision:

| | Request through the adapter | `AuthorizationChecker` |
|---|---|---|
| Decides with | the full chain: tracing, auditing, metrics, rate limiting, shadow mode, your `CoSecAuthorizationCustomizer`s | `SimpleAuthorization` alone |
| Audit events, traces, metrics | yes | none |
| Rate limits | spends a permit, can answer `429` | never spends, never limited |
| Security context | the request's | a copy (principal and tenant only), marked as a policy evaluation |
| On an error | `500` | `false`, with a warning in the log |
| Result | an HTTP status | `true` or `false` |

Because each check runs on its own copy of the security context, path variables a check captures never leak into
the caller's context, and custom matchers can recognize a check by `securityContext.isPolicyEvaluation` and skip
side effects.

## Object-level rules

Whether a user may change *this* order depends on the order: its owner, tenant or status. Those live in your
database, and the adapter runs before your handler has loaded anything, so an object-level rule is decided in two
steps:

1. **In the adapter**, the route is authorized as usual. The resource is unknown there, so every `resource.<name>`
   part is empty.
2. **In your code**, after loading the object, a check passes its attributes as `resource`, and conditions read
   them as `resource.<name>`.

Write object rules as **denies that apply only when the resource is known**. The same path then passes the adapter
and is denied in the check for anyone but the owner:

```json
{
  "name": "only-own-orders",
  "effect": "deny",
  "action": "/orders/**",
  "condition": { "bool": { "and": [
    { "eq": { "part": "resource.ownerId", "value": "", "negate": true } },
    { "eq": { "part": "resource.ownerId", "value": "#{principal.id}", "negate": true } }
  ] } }
}
```

```kotlin
val order = orderRepository.findById(id)
val allowed = checker.check(
    securityContext,
    CheckRequest("/orders/$id", "PUT", resource = mapOf("ownerId" to order.ownerId)),
).awaitSingle()
if (!allowed) throw AccessDeniedException()
```

An allow that needs a resource part would deny the route in the adapter, where the part is empty: keep allows on
what the request itself carries, and put object conditions on denies. The check must run in your code; a handler
that skips it is not protected by the rule. Conditions and templates are described in
[Conditions](../policies/language.md#conditions) and [Templates](../policies/language.md#templates).

## Explaining a decision

To see why a principal gets a decision for a request, without sending the real request, ask the explainer. It
decides with the same code as a real request, against a snapshot of the repositories, then walks every policy
statement and role permission and says which matched, which missed on the action, and which missed on the
condition. Nothing is spent or changed: rate limits are only listed, and custom matchers see
`securityContext.isPolicyEvaluation`.

The explainer is off by default, because an explanation reveals your policy structure. Enable it with
`cosec.explain.enabled=true`. With Spring Boot Actuator, also expose the endpoint:

```yaml
cosec:
  explain:
    enabled: true
management:
  endpoints:
    web:
      exposure:
        include: cosecExplain
```

The endpoint goes through CoSec like any other request: add a policy that allows `POST /actuator/cosecExplain`
for your administrators only.

```bash
curl -X POST localhost:8080/actuator/cosecExplain -H 'Content-Type: application/json' -d '{
  "principal": { "id": "user-1", "tenantId": "t1", "roles": ["clerk"], "policies": ["orders"] },
  "request":   { "path": "/orders/42", "method": "DELETE", "appId": "shop" },
  "exhaustive": false,
  "detail": "summary"
}'
```

**Input.** `principal` has `id`, `tenantId`, `roles`, `policies` and `attributes`. `request` has `path`, `method`
(`GET`), `remoteIp` (`127.0.0.1`), `appId`, `spaceId`, `deviceId`, `origin`, `referer`, `headers`, `query`,
`cookies` and `attributes`. To explain an object-level check, pass the resource as attributes named
`resource.<name>`, for example `"attributes": { "resource.ownerId": "user-2" }`. `exhaustive: true` keeps evaluating
the layers after the decision; `detail: "full"` adds each matcher's configuration.

To explain what a real caller gets, leave out `principal` and put their credential in the request's headers, for
example `"headers": { "Authorization": "Bearer eyJ..." }` or `{ "X-API-Key": "..." }`. The application's own
security context parser reads it, including revocation and API keys. A rejected credential is explained as the
anonymous request it becomes, with its real `401` reason and a `TOKEN_INVALID` warning.

**Output.**

| Field | Meaning |
|---|---|
| `status`, `authorized`, `reason` | The decision, mapped to a status [as for a real request](./web.md#responses-and-status-codes), except that rate limits never make it `429`. |
| `decidedBy` | The rule that decided: layer, policy id and statement index, or role and permission id. `null` when nothing matched. |
| `layers` | `BLACKLIST`, `ROOT`, `GLOBAL_POLICY`, `PRINCIPAL_POLICY`, `ROLE_PERMISSION`, each `EVALUATED`, `NOT_EVALUATED` (an earlier explicit deny ended evaluation), `EMPTY`, or `EVALUATED_AFTER_DECISION` (with `exhaustive: true`). Every statement and permission has an outcome: `MATCHED`, `ACTION_MISS`, `CONDITION_MISS`, `NOT_EVALUATED` (its policy's or app's condition did not match) or `ERROR`. |
| `overriddenAllows` | Allows that a later explicit deny overrode. |
| `rateLimitRules` | The [rate limit rules](../policies/rate-limiting.md) the request would spend a permit from, if it is allowed. |
| `warnings` | `MISSING_POLICY` (the principal lists a policy the repository lacks, so a real request gets `500`), `NO_APP_PERMISSION` (roles in the space, but no app permission for the app), `NO_ROLE_IN_SPACE` (roles, but none written for the request's space; see [Roles and spaces](./authentication.md#roles-and-spaces)), `MATCHER_ERROR`, `TOKEN_INVALID` (the credential was rejected), and `TRACE_INCONSISTENT` (the walk disagrees with the decision: trust the decision, and report it as a bug). |

Without Actuator, inject the `AuthorizationExplainer` bean and call `explain(request, principal)` (or
`explain(request)` to use the request's own credential), for example in a test that pins down who may do what.
For test cases over policy files, see [Testing Policies](../policies/testing.md).

**Next:** every property and its default, in [Configuration](../operations/configuration.md); or, to write the rules themselves, [Policy language](../policies/language.md).

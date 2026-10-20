# Web Integration

CoSec plugs into Spring WebFlux, Spring MVC and Spring Cloud Gateway. All three run the same
`SecurityPipeline` (parse the request, resolve the security context, authorize, map the decision to a
response), so they respond identically.

| Stack | Capability | Component | Order |
|---|---|---|---|
| Spring WebFlux | `webflux-support` | `ReactiveAuthorizationFilter` (`WebFilter`) | `1000` |
| Spring MVC | `webmvc-support` | `AuthorizationFilter` (servlet `Filter`) | servlet filter chain |
| Spring Cloud Gateway | `gateway-support` | `AuthorizationGatewayFilter` (`GlobalFilter`) | `HIGHEST_PRECEDENCE + 10` |

When Spring Cloud Gateway is on the classpath, the gateway filter replaces the WebFlux filter.

## Responses

Every request that passes path validation gets a `CoSec-Request-Id` response header: the caller's own
value if it sent one, otherwise a generated id. The gateway also forwards it to the downstream service.

Rejected requests get a JSON body, `{"authorized": false, "reason": "..."}`, and a status:

| Situation | Status | Reason |
|---|---|---|
| Path with `.` or `..` segments (also percent-encoded) or a malformed percent-escape | `400` | — (no body) |
| Denied, anonymous caller | `401` | `Implicit Deny`, `Explicit Deny`, `Token Expired` or `Token Invalid` |
| Denied, authenticated caller | `403` | `Implicit Deny` or `Explicit Deny` |
| [Rate limit](./rate-limiting.md) exhausted | `429` | `Too Many Requests` |
| Regex condition over its time budget | `403` | `Implicit Deny` |
| Any other error (including an unreachable policy store, or a principal policy the store does not have) | `500` | `Implicit Deny` |

When a rate limit rule matched, allowed and rejected responses also carry the `RateLimit-Limit`,
`RateLimit-Remaining` and `RateLimit-Reset` headers, and `429` responses carry `Retry-After` (see
[Responses](./rate-limiting.md#responses)).

Errors deny. The only deliberate exceptions are two documented degraded modes: rate limit rules without
`strictFailure`, and the [token revocation check](./authentication.md#logout-and-revocation).

## Accessing the security context

After authorization, the security context (principal, tenant, the parsed request) is available to
your code.

**WebFlux / Gateway**: from the Reactor context, or from the exchange:

```kotlin
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.getSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext

fun currentUser(): Mono<String> =
    Mono.deferContextual { context -> Mono.just(context.getSecurityContext().principal.id) }

// or, with an exchange at hand
val securityContext = exchange.getSecurityContext()
```

For authorized requests the exchange's principal is set too, so `exchange.getPrincipal()` returns the CoSec
principal.

**Spring MVC**: from the thread-bound holder, or from the request:

```kotlin
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.servlet.ServletRequests.getSecurityContext

val securityContext = SecurityContextHolder.requiredContext
// or
val securityContext = httpServletRequest.getSecurityContext()
```

The holder is cleared when the request completes.

**Without authorization.** A service behind a verifying gateway can enable
[`cosec.inject.enabled`](./authentication.md#behind-a-gateway) to populate the security context without
running authorization.

## Checking permissions in code

To ask whether the current principal may do something, without making the request, for example to decide
which buttons a page shows or to guard a message consumer, inject the `AuthorizationChecker` bean. A check is
described like the request it stands for: a path and method, and optionally an app, space, headers,
attributes, and the `resource` it targets (see [Object-level rules](./policies.md#object-level-rules)). When you leave out the app and space, the check uses those of the current request; it also keeps
the current client IP.

```kotlin
@RestController
class PermissionController(private val checker: AuthorizationChecker) {
    // WebFlux; with Spring MVC, take SecurityContextHolder.requiredContext instead of the exchange
    @PostMapping("/permissions/check")
    fun check(@RequestBody actions: Map<String, CheckRequest>, exchange: ServerWebExchange): Mono<Map<String, Boolean>> =
        checker.checkAll(exchange.getSecurityContext(), actions)
}
```

A UI then posts `{"edit": {"path": "/orders/42", "method": "PUT"}, "delete": {"path": "/orders/42", "method": "DELETE"}}`
and gets `{"edit": true, "delete": false}`. The endpoint itself needs a policy that allows authenticated callers.

Checks decide exactly like the authorization of a real request (same policies, roles, root and blacklist), but
they are queries: they write no audit events, spend no rate limit, and run on their own copy of the security
context. A check that fails to evaluate answers `false`. A check answers what the policies allow, not what the
target endpoint will do with the request, so keep enforcing on the real request.

## App and space

`CoSec-App-Id` and `CoSec-Space-Id` (header, or query parameter of the same name) name the app and the space a
request is evaluated in. The client sets them, and CoSec scopes role grants by them:

- Role grants apply only in the app they were made in: a different `CoSec-App-Id` reaches that app's grants,
  never another app's.
- With `CoSec-Space-Id`, only the principal's roles written `role@<that space>` apply. Without it (the default
  space), only roles without a suffix apply. A space the principal holds no role in gets no role grants.

The space is bound to the principal's credential: naming another space never picks up roles the token does not
state. The app id is not: CoSec takes it from the client as is. A principal holding a role in two apps can send
`CoSec-App-Id` of one app with a path of the other, and if both apps' permissions match that path (apps that share
path namespaces, such as `/api/**`), the request is authorized by the grants of the app the client named. When
several apps share a gateway or path namespace, bind the app id at the edge: have the gateway route set or
overwrite `CoSec-App-Id` per route, or add a global deny for a path prefix whose condition checks that
`request.appId` names the app that owns it. Resolving and verifying the app and space before authorization is the long-term fix,
proposed as `ScopeResolver` in [RFC 0001](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0001-target-architecture.md).

Policies see both values as `request.appId` and `request.spaceId` and can constrain them further. See
[Role grant scope](./policies.md#role-grant-scope).

## Client IP behind proxies

Policies and audit events use the client IP (`request.remoteIp`). Behind proxies, set
[`cosec.authorization.remote-ip.max-trusted-index`](./configuration.md#authorization) to the number of
proxy hops you trust, so the IP comes from the right `X-Forwarded-For` entry and cannot be spoofed by the
client. The IP is never resolved through reverse DNS.

## Request attributes

Request attributes are string values attached to the request before authorization. Conditions read them as
`request.attributes.<name>`, and they appear in audit events.

| Attribute | Added by | Value |
|---|---|---|
| `ipRegion` | `ip2region-support` | The client IP's location (country, region, city, ISP), looked up in an embedded [ip2region](https://github.com/lionsoul2014/ip2region) database. Absent if the lookup fails. |
| `cosec.audit.routeId` | `gateway-support`, with auditing enabled (the default) | The id of the matched gateway route. |

```json
{ "contains": { "part": "request.attributes.ipRegion", "value": "中国" } }
```

To add your own attributes, provide a [`RequestAttributesAppender`](../architecture/extending.md#request-and-security-context)
bean.

## CORS

Browsers send CORS preflight requests without credentials. With WebFlux, register `OrderedCorsWebFilter`
(order `999`, before authorization) so preflights are answered before they reach authorization:

```kotlin
@Bean
fun corsWebFilter(source: CorsConfigurationSource) = OrderedCorsWebFilter(source)
```

## Gateway notes

- The gateway filter forwards `CoSec-Request-Id` downstream and adds the matched route id as a
  [request attribute](#request-attributes).
- [`cosec-gateway-server`](./operations.md#deployment) is a ready-to-run gateway built on this filter.

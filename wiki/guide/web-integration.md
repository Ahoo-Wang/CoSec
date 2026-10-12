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
| Any other error (including an unreachable policy store) | `500` | `Implicit Deny` |

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

# Web 集成 {#web-integration}

CoSec 接入 Spring WebFlux、Spring MVC 和 Spring Cloud Gateway。三者运行同一条 `SecurityPipeline`
（解析请求、解析安全上下文、授权、把决策映射为响应），因此响应行为完全一致。

| 技术栈 | 能力 | 组件 | 顺序 |
|---|---|---|---|
| Spring WebFlux | `webflux-support` | `ReactiveAuthorizationFilter`（`WebFilter`） | `1000` |
| Spring MVC | `webmvc-support` | `AuthorizationFilter`（Servlet `Filter`） | Servlet 过滤器链 |
| Spring Cloud Gateway | `gateway-support` | `AuthorizationGatewayFilter`（`GlobalFilter`） | `HIGHEST_PRECEDENCE + 10` |

classpath 上有 Spring Cloud Gateway 时，网关过滤器取代 WebFlux 过滤器。

## 响应 {#responses}

通过路径校验的每个请求都会带上 `CoSec-Request-Id` 响应头：调用方传了就沿用，否则生成一个。网关还会把它转发给下游服务。

被拒绝的请求返回 JSON 响应体 `{"authorized": false, "reason": "..."}`，状态码如下：

| 情形 | 状态码 | 原因 |
|---|---|---|
| 路径含 `.` 或 `..` 段（包括百分号编码形式），或百分号转义格式错误 | `400` | —（无响应体） |
| 被拒绝，匿名调用方 | `401` | `Implicit Deny`、`Explicit Deny`、`Token Expired` 或 `Token Invalid` |
| 被拒绝，已认证调用方 | `403` | `Implicit Deny` 或 `Explicit Deny` |
| 限流许可用尽 | `429` | `Too Many Requests` |
| 正则条件超出时间预算 | `403` | `Implicit Deny` |
| 其他任何错误（包括策略存储不可达） | `500` | `Implicit Deny` |

出错即拒绝。仅有的两个例外是有文档说明的降级模式：未设置 `strictFailure` 的 Redis 限流器，以及
[令牌吊销检查](./authentication.md#logout-and-revocation)。

## 获取安全上下文 {#accessing-the-security-context}

授权完成后，你的代码可以获取安全上下文（主体、租户、解析后的请求）。

**WebFlux / Gateway**：从 Reactor 上下文或 exchange 中获取：

```kotlin
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.getSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext

fun currentUser(): Mono<String> =
    Mono.deferContextual { context -> Mono.just(context.getSecurityContext().principal.id) }

// 或者，手上有 exchange 时
val securityContext = exchange.getSecurityContext()
```

对于已授权的请求，exchange 的 principal 也会被设置，因此 `exchange.getPrincipal()` 返回的就是 CoSec 主体。

**Spring MVC**：从线程绑定的 holder 或请求中获取：

```kotlin
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.servlet.ServletRequests.getSecurityContext

val securityContext = SecurityContextHolder.requiredContext
// 或者
val securityContext = httpServletRequest.getSecurityContext()
```

请求结束时 holder 会被清理。

**不做授权时。** 位于校验网关之后的服务可以启用
[`cosec.inject.enabled`](./authentication.md#behind-a-gateway)，只填充安全上下文而不执行授权。

## 代理之后的客户端 IP {#client-ip-behind-proxies}

策略和审计事件使用客户端 IP（`request.remoteIp`）。位于代理之后时，请把
[`cosec.authorization.remote-ip.max-trusted-index`](./configuration.md#authorization) 设为你信任的代理层数，
这样 IP 会取自 `X-Forwarded-For` 中正确的那一项，客户端无法伪造。IP 从不通过反向 DNS 解析。

## 请求属性 {#request-attributes}

请求属性是在授权之前附加到请求上的字符串值。条件以 `request.attributes.<name>` 读取它们，审计事件中也会包含它们。

| 属性 | 由谁添加 | 取值 |
|---|---|---|
| `ipRegion` | `ip2region-support` | 在内置 [ip2region](https://github.com/lionsoul2014/ip2region) 数据库中查到的客户端 IP 位置（国家、地区、城市、运营商）。查询失败时不存在。 |
| `cosec.audit.routeId` | `gateway-support`，且启用了审计（默认启用） | 命中的网关路由 id。 |

```json
{ "contains": { "part": "request.attributes.ipRegion", "value": "中国" } }
```

要添加自己的属性，请提供一个 [`RequestAttributesAppender`](../architecture/extending.md#request-and-security-context) Bean。

## CORS {#cors}

浏览器发送的 CORS 预检请求不带凭证。使用 WebFlux 时，请注册 `OrderedCorsWebFilter`（顺序 `999`，在授权之前），
让预检请求在到达授权之前就得到响应：

```kotlin
@Bean
fun corsWebFilter(source: CorsConfigurationSource) = OrderedCorsWebFilter(source)
```

## 网关说明 {#gateway-notes}

- 网关过滤器会把 `CoSec-Request-Id` 转发给下游，并把命中的路由 id 作为[请求属性](#request-attributes)加入。
- [`cosec-gateway-server`](./operations.md#deployment) 是基于该过滤器、开箱即用的网关。

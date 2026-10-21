# Web 集成 {#web-integration}

CoSec 接入 Spring WebFlux、Spring MVC 和 Spring Cloud Gateway。三者运行同一条 `SecurityPipeline`
（解析请求、解析安全上下文、授权、把决策映射为响应），因此响应行为完全一致。

| 技术栈 | 能力 | 组件 | 顺序 |
|---|---|---|---|
| Spring WebFlux | `webflux-support` | `ReactiveAuthorizationFilter`（`WebFilter`） | `1000` |
| Spring MVC | `webmvc-support` | `AuthorizationFilter`（Servlet `Filter`） | Servlet 过滤器链 |
| Spring Cloud Gateway | `gateway-support` | `AuthorizationGatewayFilter`（`GlobalFilter`） | `HIGHEST_PRECEDENCE + 10` |

classpath 上有 Spring Cloud Gateway 时，网关过滤器取代 WebFlux 过滤器。

**策略看到的路径**（`request.path` 和 `path` 动作）是请求发来时的路径，保持百分号编码，并去掉 `;` 路径参数
（`/orders;jsessionid=1/42` 视为 `/orders/42`）。在 Spring MVC 上，它是去掉 Servlet context path 之后的请求 URI，
因此保留 `DispatcherServlet` 的映射前缀：设置 `spring.mvc.servlet.path=/api` 时，`/api/admin/users` 按 `/api/admin/users` 匹配。
在 WebFlux 和 Gateway 上，它是完整的请求路径。

## 响应 {#responses}

通过路径校验的每个请求都会带上 `CoSec-Request-Id` 响应头：调用方传了就沿用，否则生成一个。网关还会把它转发给下游服务。

被拒绝的请求返回 JSON 响应体 `{"authorized": false, "reason": "..."}`，状态码如下：

| 情形 | 状态码 | 原因 |
|---|---|---|
| 路径含 `.` 或 `..` 段（包括百分号编码形式，或带路径参数的形式如 `..;`），或百分号转义格式错误 | `400` | —（无响应体） |
| 被拒绝，匿名调用方 | `401` | `Implicit Deny`、`Explicit Deny`、`Token Expired` 或 `Token Invalid` |
| 被拒绝，已认证调用方 | `403` | `Implicit Deny` 或 `Explicit Deny` |
| [限流](./rate-limiting.md)许可用尽 | `429` | `Too Many Requests` |
| 正则条件超出时间预算 | 匿名 `401`，已认证 `403` | `Implicit Deny` |
| 其他任何错误（包括策略存储不可达，或主体引用的策略在存储中不存在） | `500` | `Implicit Deny` |

有限流规则匹配时，放行和拒绝的响应都会带上 `RateLimit-Limit`、`RateLimit-Remaining` 和 `RateLimit-Reset` 响应头，
`429` 响应还会带上 `Retry-After`（见[响应](./rate-limiting.md#responses)）。

出错即拒绝。仅有的两个例外是有文档说明的降级模式：未设置 `strictFailure` 的限流规则，以及
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
跳过签名校验的解析器只在 `cosec.authorization.enabled=false` 时使用；只要授权开启，inject 过滤器就使用会校验签名的解析器，与自动配置的顺序无关。

## 在代码中检查权限 {#checking-permissions-in-code}

想知道当前主体能否执行某个操作，又不真的发出请求时，例如决定页面上显示哪些按钮，或者在消息消费者中做校验，
可以注入 `AuthorizationChecker` bean。检查的写法和它代表的请求一样：路径和方法，以及可选的应用、空间、请求头、属性，以及它针对的 `resource`（见[对象级规则](./policies.md#object-level-rules)）。
不指定应用和空间时，检查沿用当前请求的应用和空间，也会沿用当前的客户端 IP。

```kotlin
@RestController
class PermissionController(private val checker: AuthorizationChecker) {
    // WebFlux；Spring MVC 下改用 SecurityContextHolder.requiredContext 代替 exchange
    @PostMapping("/permissions/check")
    fun check(@RequestBody actions: Map<String, CheckRequest>, exchange: ServerWebExchange): Mono<Map<String, Boolean>> =
        checker.checkAll(exchange.getSecurityContext(), actions)
}
```

前端提交 `{"edit": {"path": "/orders/42", "method": "PUT"}, "delete": {"path": "/orders/42", "method": "DELETE"}}`，
得到 `{"edit": true, "delete": false}`。这个端点本身也需要一条放行已认证调用方的策略。

检查的决策方式与真实请求的授权完全相同（同样的策略、角色、root 和黑名单），但它是查询：不写审计事件、不消耗限流许可，
并在自己的安全上下文副本上运行。求值失败的检查返回 `false`。检查回答的是策略允许什么，而不是目标端点会如何处理请求，
因此真实请求上的授权仍然要保留。

## 应用与空间 {#app-and-space}

`CoSec-App-Id` 和 `CoSec-Space-Id`（请求头，或同名查询参数）指定请求在哪个应用、哪个空间中评估。它们由客户端设置，
CoSec 据此限定角色授权的作用域：

- 角色授权只在做出授权的应用中生效：换一个 `CoSec-App-Id` 只会得到那个应用的授权，不会得到别的应用的授权。
- 带 `CoSec-Space-Id` 时，只有主体写成 `role@<该空间>` 的角色生效；不带时（默认空间），只有不带后缀的角色生效。
  主体在某个空间中没有角色，就得不到任何角色授权。

空间与主体的凭证绑定：指定其他空间永远不会得到令牌中没有声明的角色。应用 id 则不然：CoSec 原样采用客户端给出的值。
在两个应用中都持有角色的主体，可以发送其中一个应用的 `CoSec-App-Id`，却访问另一个应用的路径；如果两个应用的权限都匹配
该路径（共享路径命名空间的应用，例如 `/api/**`），请求就会按客户端指定的那个应用的授权被放行。当多个应用共享网关或路径
命名空间时，请在边缘绑定应用 id：让网关路由按路由设置或覆盖 `CoSec-App-Id`，或者为某个路径前缀添加一条全局拒绝，
用条件检查 `request.appId` 是否为拥有该前缀的应用。在授权之前解析并校验应用与空间才是长期方案，即 [RFC 0001](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0001-target-architecture.md) 中提出的
`ScopeResolver`。

策略可以通过 `request.appId` 和 `request.spaceId` 读取这两个值，进一步加以约束。见[角色授权的作用域](./policies.md#role-grant-scope)。

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

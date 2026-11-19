---
title: "代码中的权限检查"
description: "在自己的代码中向 CoSec 请求决策，落实对象级规则，并解释一个决策为什么是这个结果。"
---

# 代码中的权限检查 {#checks-in-code}

面向规则不只取决于路由的开发者：显示或隐藏界面操作、检查已加载的对象，或诊断一个决策。本页介绍直接询问引擎的 `AuthorizationChecker`，以及说明决策原因的控制面 `explain`。

## 什么时候在代码中检查 {#when-to-check-in-code}

[Web 适配器](./web.md)在处理器运行之前已经授权了每个请求。只有在需要适配器给不出的答案时，才在代码中检查：

- **用户下一步能做什么？** 页面决定显示哪些按钮，而不实际发送这些请求。
- **这个对象是他的吗？** 答案取决于你在处理器中加载的数据，比如订单的所有者（[对象级规则](#object-level-rules)）。

不要在处理器中重复请求本身的检查：适配器已经做出了决策，并完成了审计和限流，再检查一次只可能与它不一致。检查是为下一步提供的建议，而不是对当前请求的强制执行。

检查针对的是**正在处理的请求**，使用该请求的 `SecurityContext`。它不适用于消息消费者、调度器或请求之后运行的任何工作：见[不是请求的工作](#work-that-is-not-the-request)。

## 检查一个权限 {#checking-a-permission}

starter 会注册一个 `AuthorizationChecker` Bean（`me.ahoo.cosec.runtime`）。它针对管道已放行的请求的主体和作用域做决策，因此需要该请求的 `SecurityContext`（[访问安全上下文](./web.md#accessing-the-security-context)；测试中用工具包构造的上下文：[测试你的应用](../policies/testing.md#testing-your-application)）：

```kotlin
suspend fun check(security: SecurityContext, action: String, resource: String): Boolean
```

- `action` 为 `service:verb`，与路由映射中的命名一致（`order:cancel`），未映射的路由则为 `http:<METHOD>`。检查不应用路由映射，因此无论路由映射是否映射了该路径，`http:` 动作都按字面决策（[检查与已映射的路由](#a-check-and-a-mapped-route)）。不含 `:` 的字符串结果为 `false`。
- `resource` 为类型化资源 `type/id`（`order/42`），或以 `/` 开头的路径（`/orders/42`），后者是一个 `http` 资源。它按管道读取请求的方式读取：去掉路径参数；管道会[以 `400` 拒绝](./web.md#the-request-path)的路径为 `false`，超过请求路径长度上限的资源也是 `false`。因此即使路径来自客户端输入，检查也不会放行任何请求都无法获准的路径。

以 `cosec.pipeline.mode=gateway` 运行在网关之后的服务没有检查器：它不持有可用于决策的策略（[网关之后的服务](./web.md#services-behind-the-gateway)）。在这样的服务中，依靠网关的决策，或者以 `enforce` 模式运行该服务。

两个适配器模块提供了非挂起的形式：

| 模块 | 函数 | 使用场景 |
|---|---|---|
| `cosec-webflux` | `AuthorizationChecker.checkMono(security, action, resource)`：`Mono<Boolean>` | WebFlux 和网关代码。它在调用方线程上开始，并且不会在客户端库的 I/O 线程上继续。 |
| `cosec-webmvc` | `AuthorizationChecker.checkBlocking(security, action, resource)`：`Boolean` | Servlet 请求线程。在非阻塞线程（例如 Netty 事件循环）上调用会抛出异常：那里请用 `checkMono`。 |

每种技术栈一个示例，各带导入。每个示例都在问：调用方可以对某个订单执行哪些操作？

```kotlin
// WebFlux，返回 Mono
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
// WebFlux，suspend 处理器：exchange 参数给出上下文，check 是挂起函数
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
// Spring MVC：SecurityContext 参数，在请求线程上用 checkBlocking
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

这个端点本身也是普通路由：你的文档必须放行它。

### 检查背后的决策 {#the-decision-behind-a-check}

`decide` 接收相同的参数，返回 `check` 归结为布尔值之前的那个 `Decision`（`me.ahoo.cosec.model.decision`）。`check` 就是 `decide(...) is Decision.Permit`，因此两者的结果始终一致：

```kotlin
suspend fun decide(security: SecurityContext, action: String, resource: String): Decision
```

| 决策 | 何时 | `check` |
|---|---|---|
| `Permit(by)` | 有 ALLOW 匹配，且没有 DENY 匹配。`by` 是放行的语句：`policy`、`index`、`name`，以及 `via`，即策略生效的途径。`BindingRef.Bound(id, document)` 是一个绑定，位于 `PLATFORM`、`APP` 或 `TENANT` 文档中；`BindingRef.Attached(policy)` 是主体的成员关系附加的策略。 | `true` |
| `Deny(by)` | 有 DENY 匹配（`by` 为该语句），租户的平台边界拒绝了放行（`StatementRef.Boundary`），或者没有任何匹配（`null`）。管道没有构建的 `SecurityContext`、不含 `:` 的动作，或者管道会拒绝的资源，也是 `Deny(null)`。 | `false` |
| `Indeterminate(cause)` | 无法作出决策：某个文档读不到，某个 DENY 求值出错，检查本身出错，或者只有 break-glass 会放行。检查本身出错时日志中会有一条警告，并报告为 `CheckFailed(error)`：看 `error` 就知道出错的是什么。检查读不到某个文档时是 `SourceUnavailable`，与请求时一样。 | `false` |

用它记录是什么允许了你的代码执行某个操作，例如“由应用文档中的角色绑定 `order-admins` 放行”。`Decision` 只留在你的进程中：永远不要把它或它的原因写进响应。Web 适配器也从不接触它，所有状态码正是因此出自同一处（[响应与状态码](./web.md#responses-and-status-codes)）。出于同样的原因，适配器模块不提供 `decide` 的 `Mono` 或阻塞形式：请在协程中调用它。`Permit` 携带的义务（即限流）并没有执行。

### 检查与请求管道的关系 {#how-a-check-relates-to-the-request-pipeline}

检查与请求使用相同的引擎、相同的文档和相同的规则，遵循同样的[求值顺序](../guide/security-model.md#evaluation-order)。它沿用请求的主体、成员关系、作用域（租户、应用、空间和角色）以及环境（客户端 IP、设备），不同之处只在决策之外：

| | 经过适配器的请求 | `AuthorizationChecker` |
|---|---|---|
| 动作和资源 | 来自路由映射 | 你传入的值，按请求的方式读取：`http` 路径经规范化，`http:HEAD` 按 `http:GET`，含小写字母的 `http` 动词或执法拒绝的路径为拒绝（`PIP-15`、`PIP-18`） |
| 快照文档 | 为请求读取 | 为检查重新读取 |
| 外部属性 | 每个请求获取 A 阶段属性，按计划获取 B 阶段属性 | 为检查的动作和资源重新获取：它可能读取的每个属性，都向声明它的提供者获取（A 阶段提供者与 A 阶段一样不带作用域） |
| 决策监听器（审计、指标、追踪） | 有 | 无 |
| 限流 | 消耗许可，可能返回 `429` | 从不消耗，也从不受限 |
| 只有 break-glass 放行的 Permit | 在 REQUIRED 审计之后继续 | `false`：检查不写审计 |
| Deny、Indeterminate、错误 | 一个状态码 | `false`，出错时日志中有一条警告 |
| 结果 | HTTP 状态码 | 只有 Permit 才为 `true`；`decide` 返回决策本身 |

检查失败即拒绝：不是由管道构建的 `SecurityContext`（你自己构造的，或者根本没有）一律为 `false`，不论文档怎么写。因此请求之外不存在检查。

检查不会重新认证。它使用请求被放行时解析出的主体、成员关系和作用域做决策，并重新读取文档。它不检查令牌的年龄和吊销，也看不到之后角色的变化。因此在请求进行期间，检查会在用户登出、`revokeSubject` 或角色被移除之后继续放行，只要文档允许；请求本身已经决策过了。请求结束时检查随之停止（`PIP-26`，见下文）；你自己的传输通过关闭 `Outcome` 结束请求。不要保存上下文：在请求到达处授权，把决策而不是上下文随工作一起传递。

### 检查与已映射的路由 {#a-check-and-a-mapped-route}

检查不应用路由映射。`check(security, "http:POST", "/orders/42/cancel")` 按 `http:POST` 作用于 `http:path:/orders/42/cancel` 决策，与未映射的请求一样。当路由映射映射了该请求时，请求本身按它的 `service:verb` 和类型化资源决策，而 `http:` 上的语句从不覆盖它（[路由映射](./web.md#route-maps)）。因此对同一路径，检查与请求可能做出不同的决策：一条放行 `order:cancel`、在 `http:` 上没有任何内容的策略会放行请求并拒绝检查，而一条 `http:*` 的 ALLOW 会放行检查而不放行请求。要问某条已映射的路由会被如何决策，就检查路由映射给它的动作和类型化资源（`order/42` 上的 `order:cancel`）。

## 不是请求的工作 {#work-that-is-not-the-request}

消息消费者、调度器、重试、请求启动的作业：它们都没有可以作为其检查对象的请求。应当这样做：

- **在请求到达的地方授权，并把决策随消息带走。** 适配器在你的处理器运行之前已经决策了该请求；在请求仍在处理时，在那里为后续要做的事增加一次检查（`order:cancel` 作用于 `order/42`）。只有放行时才发布这项工作，让消费者据此消息行动，而不再询问 CoSec。消息携带的是该动作已获授权这一事实，而不是上下文：想知道用户身份的消费者，从消息自己的字段（主体 id、租户）读取。消费者因此信任的是它自己的通道对消息的保证，这一点 CoSec 不提供。
- **在没有过滤器的端口上运行管道，而不是手工验证令牌。** 内部端口上的服务调用、WebSocket 握手，或携带令牌的消息头，都可以交给 Web 适配器所调用的同一个 `Pipeline.evaluate`（[管道在适配器之外](../architecture/extending.md#pipeline-outside-the-adapters)）。如果必须自己验证令牌，就做完整：[在管道之外验证令牌](./authentication.md#verifying-a-token-outside-the-pipeline)。
- **系统自己启动的工作**（调度器、定时器、没有调用方的系统命令）在 8.0 中不由 CoSec 决策。没有可以作为其决策对象的主体，没有上下文的检查为 `false`。在你自己的代码中决策，并且让人读得明白：不要因为检查说 `false` 就退回到放行一切的默认值。以主体身份对请求之外的工作做决策，并提供针对具名服务主体的进程内入口，计划在 8.1 提供。

检查属于它的请求，绑定该请求的适配器在请求完成时结束它（`PIP-26`）：

- **请求仍在进行时**，检查按上表作答，无论请求持续多久：在流式或服务器发送事件（SSE）响应体写出期间、在异步 Servlet 请求中、在处理器等待的代码里都是如此。它按请求开始时的主体、成员关系和属性判定，即使令牌已在途中过期：管道在请求开始时认证了它，检查不比请求自己的决策更严。
- **请求结束之后**，对它的上下文的每次检查都为 `false`（`decide` 返回 `Deny(null)`），不论文档怎么写：保存在字段、缓存或消息里的上下文之后不再放行任何操作。WebFlux 和网关在响应写完、失败或客户端离开时结束请求，并且在把完成信号传递下去之前结束，所以看到请求完成的代码看到的是已结束的请求；Spring MVC 在分派返回时结束，异步请求（`DeferredResult`、`StreamingResponseBody`、`suspend` 处理器）则在它完成、失败或超时时结束。异步请求的 ASYNC 分派中的代码（消息转换器、`ResponseBodyAdvice`、拦截器）仍在请求之内。错误页不在：Spring MVC 在请求结束之后的 ERROR 分派中渲染它，所以错误页或错误控制器读得到上下文，但在那里对它的每次检查都为 `false`。
- **你自己的传输交给 `Pipeline.evaluate` 的请求**在传输关闭其 `Outcome`（`AutoCloseable`）时结束：响应在代码块返回时已完成的，用 `evaluate(facts).use { … }` 包住；响应比处理器活得更久的（流式响应体、WebSocket 会话），在响应的完成回调里调用 `outcome.close()`。在处理器返回时关闭，会在这种流还在写出时就结束请求，流中的检查都为 `false`。从不关闭它的传输，会让请求在其上下文被持有期间一直处于进行中（[你自己的传输](../architecture/extending.md#pipeline-outside-the-adapters)）。
- **测试工具包的上下文**在测试结束它们之前一直处于进行中，就像主机在响应完成时那样：`PipelineFixture.end(context)`、`PermittedRequest.end(context)`，或者关闭 `PipelineFixture.evaluate` 的 `Outcome`。测试"在响应之后检查"的代码时，先结束上下文，再断言检查为 `false`。

## 对象级规则 {#object-level-rules}

用户能否修改*这个*订单，取决于订单本身：它的所有者、租户或状态。适配器在你的处理器加载任何数据之前运行，因此对象级规则分两步决定：

1. **在适配器中**，按请求本身携带的信息授权路由，例如 `order/42` 上的 `order:cancel`。
2. **在你的代码中**，加载对象之后，检查一个其 id 携带了规则所需事实的资源。

检查不传资源属性，只传类型和 id。因此把事实写进 id，每个事实占一段，再让语句捕获它们。这里的资源 `order-of/<owner>/<status>/<orderId>` 携带订单的所有者和状态，下面的策略绑定给任何人（`"subject": "*"`）：

<<< @/examples/checks/documents/platform.json [documents/platform.json]

```kotlin
// Spring MVC；orderRepository 和 Order 是你自己的。WebFlux 里用 checkMono，或用 suspend 处理器和 check。
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

[`wiki/examples/checks`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/checks/scenarios) 中的场景在每次构建时用[测试套件](../policies/testing.md#testing-decisions)针对这份文档运行：

| 检查 | 调用方 | 决策 |
|---|---|---|
| `order-of/alice/pending/42` 上的 `order:cancel` | `alice` | Permit |
| `order-of/alice/pending/42` 上的 `order:cancel` | `bob` | Deny：`not-the-owner` |
| `order-of/alice/shipped/42` 上的 `order:cancel` | `alice` | Deny：`already-shipped` |

`{owner}` 和 `{status}` 各捕获一段，语句在自己的条件中以 `resource.var.owner` 和 `resource.var.status` 读取它们。这两条 DENY 只作用于 `order-of/...` 资源，没有任何路由会产生这种资源，因此路由本身仍由你的其他策略决定。检查是一次独立的决策：只有另有 ALLOW 覆盖 `order-of/...` 上的 `order:cancel`（`cancel-an-order`）时才会放行，并且除所有者外一律拒绝，已发货的订单也一律拒绝。捕获的值只占一段且按原样比较；id 按 `/` 切分且从不解码，因此可能包含 `/` 的值不属于 id。资源模式和条件见[策略语言](../policies/language.md)。

检查必须在你的代码中执行；跳过它的处理器不受该规则保护。

## 解释一个决策 {#explaining-a-decision}

要在不发送真实请求的情况下了解某个主体对某个请求为什么得到某个决策，请使用控制面的 `explain`。它需要 `cosec-control` 和 `cosec-cocache`，在宿主上以 `POST <base-path>/explain` 提供（默认 `/cosec/control/v1/explain`；见[控制面路由](./web.md#control-plane-routes)）。

它与强制执行使用相同的作用域解析、文档编译器和引擎，以完整追踪级别，基于已发布的文档求值：文档的最新版本，或你指定的修订。不会消耗或改变任何东西。

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

**输入。** `request` 描述请求本身；不会从任何凭证中读取信息：

| 字段 | 含义 |
|---|---|
| `tenant` | 必填。主体所在的租户。 |
| `principal` | 主体 id；省略则为匿名主体。 |
| `roles`、`policies` | 成员关系声明，写法与令牌中相同（`role`、`role@space`；[角色与空间](./authentication.md#roles-and-spaces)）。 |
| `app`、`space` | 模拟的凭证声明的应用（在该应用的宿主上）和它选择的空间（`CoSec-Space-Id`）。 |
| `action` | 必填。`service:verb`。 |
| `resource` | 必填。`type/id`，或以 `/` 开头的路径。 |
| `remoteIp` | 客户端 IP。 |
| `time` | 请求时间，ISO-8601 时刻；默认为当前时间。 |

可选的 `at`（`{"platform": 12, "app": 3, "tenant": 7}`）基于这些仍保留的修订而不是最新版本做解释；已不再保留的修订返回 `410`。未知字段返回 `400`。

**输出。**

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

| 字段 | 含义 |
|---|---|
| `version` | 做决策所用文档的修订。 |
| `decision.decision` | `Permit`、`Deny`、`Indeterminate`，作用域被拒绝时为 `Rejected`（此时 `cause` 为 `APP_MISMATCH` 或 `SPACE_NOT_MEMBER`，且没有追踪）。 |
| `decision.by` | 作出决策的语句：策略、语句序号和名称、它生效所经的绑定（`via`：绑定 id，或 `attached:<policy>`）及其文档。隐式拒绝和 Indeterminate 时没有。 |
| `decision.cause` | Indeterminate 时原因的类型（如 `MissingPolicies`、`MatcherFailed`、`SourceUnavailable`、`DocumentInvalid`）；从不包含其消息。 |
| `decision.redacted` | 作出决策的语句你无权查看时为 `true`。 |
| `trace.candidates` | 每条被求值的语句：效果、语句本身及结果：`MATCHED`、`ACTION_MISS`、`RESOURCE_MISS`、`CONDITION_MISS`、`GUARD_MISS`（所在策略的条件不成立）、`GUARD_ERROR`、`ERROR` 或 `NOT_EVALUATED`。 |
| `trace.redacted` | 因你无权查看而省略的候选数量。 |

**谁可以解释，能看到什么。** `explain` 与所有控制面调用一样，以请求的主体身份、在应用 `cosec.control.app-id` 中授权：

- 调用方需要对被模拟租户的文档（`doc/tenant/<tenant>`）拥有 `cosec:explain`。租户的主体只能解释自己租户的请求：租户隔离最先检查，而且不是策略。内置的 `cosec:tenant-viewer` 和 `cosec:tenant-admin` 在调用方自己的租户上授予该权限，`cosec:platform-admin` 在所有租户上授予（[内置策略](../guide/security-model.md#built-in-policies)）；
- 只显示调用方有权读取（对文档拥有 `cosec:read`）的文档中的语句。经由附加策略到达的语句，只有调用方能同时读取租户文档和平台文档时才显示。其余内容从追踪中省略并计入 `redacted`；由隐藏语句作出的决策只给出 `"redacted": true`；
- 没有权限时返回 `403`，无法判定时返回 `503`，都不带细节。

**它看不到什么。** 解释读取的是已发布的文档，而不是某个实例已安装的内容（实例可能还会在一段时间内使用较旧的修订；见[存储](../operations/storage.md)）。它不获取外部属性，因此读取外部属性的语句按属性缺失处理（[外部属性缺失](../guide/security-model.md#missing-external-attributes)）；它也没有令牌属性、黑名单和限流。

**模拟一个变更。** `POST <base-path>/simulate` 接受同样的 `request`，以及可选的已暂存变更（`"candidate": "<change id>"`）：它给出当前文档上的决策（`enforced`）、应用该变更后的决策（`candidate`），以及两者是否不同。它需要对租户文档和该变更所属文档拥有 `cosec:simulate`。暂存变更、影子窗口和访问分析（`/analyze`）见[策略测试](../policies/testing.md)。

**下一步：** 每个属性及其默认值，见[配置](../operations/configuration.md)；要编写规则本身，见[策略语言](../policies/language.md)。

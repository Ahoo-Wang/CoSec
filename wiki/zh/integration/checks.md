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

## 检查一个权限 {#checking-a-permission}

starter 会注册一个 `AuthorizationChecker` Bean（`me.ahoo.cosec.runtime`）。它针对管道已放行的请求的主体和作用域做决策，因此需要该请求的 `SecurityContext`（[访问安全上下文](./web.md#accessing-the-security-context)）：

```kotlin
suspend fun check(security: SecurityContext, action: String, resource: String): Boolean
```

- `action` 为 `service:verb`，与路由映射中的命名一致（`order:cancel`），未映射的路由则为 `http:<METHOD>`。不含 `:` 的字符串结果为 `false`。
- `resource` 为类型化资源 `type/id`（`order/42`），或以 `/` 开头的路径（`/orders/42`），后者是一个 `http` 资源。

以 `cosec.pipeline.mode=gateway` 运行在网关之后的服务没有检查器：它不持有可用于决策的策略（[网关之后的服务](./web.md#services-behind-the-gateway)）。在这样的服务中，依靠网关的决策，或者以 `enforce` 模式运行该服务。

两个适配器模块提供了非挂起的形式：

| 模块 | 函数 | 使用场景 |
|---|---|---|
| `cosec-webflux` | `AuthorizationChecker.checkMono(security, action, resource)`：`Mono<Boolean>` | WebFlux 和网关代码。它在调用方线程上开始，并且不会在客户端库的 I/O 线程上继续。 |
| `cosec-webmvc` | `AuthorizationChecker.checkBlocking(security, action, resource)`：`Boolean` | Servlet 请求线程。在非阻塞线程（例如 Netty 事件循环）上调用会抛出异常：那里请用 `checkMono`。 |

```kotlin
@RestController
class OrderActionsController(private val checker: AuthorizationChecker) {
    // WebFlux：调用方可以对这个订单执行哪些操作？
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

这个端点本身也是普通路由：你的文档必须放行它。

### 检查与请求管道的关系 {#how-a-check-relates-to-the-request-pipeline}

检查与请求使用相同的引擎、相同的文档和相同的规则，遵循同样的[求值顺序](../guide/security-model.md#evaluation-order)。它沿用请求的主体、成员关系、作用域（租户、应用、空间和角色）以及环境（客户端 IP、设备），不同之处只在决策之外：

| | 经过适配器的请求 | `AuthorizationChecker` |
|---|---|---|
| 动作和资源 | 来自路由映射 | 你传入的值 |
| 快照文档 | 为请求读取 | 为检查重新读取 |
| 决策监听器（审计、指标、追踪） | 有 | 无 |
| 限流 | 消耗许可，可能返回 `429` | 从不消耗，也从不受限 |
| 只有 break-glass 放行的 Permit | 在 REQUIRED 审计之后继续 | `false`：检查不写审计 |
| Deny、Indeterminate、错误 | 一个状态码 | `false`，出错时日志中有一条警告 |
| 结果 | HTTP 状态码 | 只有 Permit 才为 `true` |

检查失败即拒绝：不是由管道构建的 `SecurityContext`（你自己构造的，或者根本没有）一律为 `false`，不论文档怎么写。因此请求之外不存在检查：代表用户执行操作的消息消费者没有可供检查的已放行请求，必须自己携带授权。

## 对象级规则 {#object-level-rules}

用户能否修改*这个*订单，取决于订单本身：它的所有者、租户或状态。适配器在你的处理器加载任何数据之前运行，因此对象级规则分两步决定：

1. **在适配器中**，按请求本身携带的信息授权路由，例如 `order/42` 上的 `order:cancel`。
2. **在你的代码中**，加载对象之后，检查一个其 id 携带了规则所需事实的资源。

检查不传资源属性，只传类型和 id。因此把事实写进 id，再让语句捕获它们。例如，把所有权检查表示为资源 `order-of/<owner>/<orderId>`，并把下面这条策略绑定给任何人（`"subject": "*"`）：

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

这条 DENY 只作用于 `order-of/...` 资源，没有任何路由会产生这种资源，因此路由本身仍由你的其他策略决定。检查是一次独立的决策：只有另有 ALLOW 覆盖 `order-of/...` 上的 `order:cancel` 时才会放行，并且除所有者外一律拒绝。捕获的值只占一段且按原样比较；id 按 `/` 切分且从不解码，因此所有者 id 不能包含 `/`。资源模式和条件见[策略语言](../policies/language.md)。

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
| `app`、`space` | 声明的应用和空间，相当于 `CoSec-App-Id` 和 `CoSec-Space-Id`；不适用宿主应用。 |
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
| `decision.cause` | Indeterminate 时原因的类型（如 `MissingPolicies`、`MatcherFailed`、`SourceUnavailable`）；从不包含其消息。 |
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

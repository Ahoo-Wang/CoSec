---
title: "代码中的权限检查"
description: "在自己的代码中向 CoSec 请求决策、实现对象级规则，并解释某个决策为什么是这个结果。"
---

# 代码中的权限检查 {#checks-in-code}

面向规则不只取决于 URL 的开发者：按权限显示或隐藏界面操作、校验已加载对象的归属，或排查某个决策。本页介绍如何直接调用授权，
而不只依赖 Web 适配器。

## 什么时候在代码中检查 {#when-to-check-in-code}

[Web 适配器](./web.md)已经在处理器运行之前授权了每个请求。只有需要适配器给不出的答案时，才在代码中检查：

- **用户接下来能做什么？** 页面要决定显示哪些按钮，但不发出这些请求。
- **这个对象是不是他的？** 规则取决于处理器中加载的数据，例如订单的所有者（[对象级规则](#object-level-rules)）。
- **不是 HTTP 请求的工作**，例如代表某个用户执行的消息消费者。

不要在处理器中重复请求本身的检查：适配器已经做出了决定，并且经过了审计和限流，再检查一次只可能和它不一致。另外，检查只是参考，
不是强制执行：对真实请求的授权要继续保留。

## 检查一个权限 {#checking-a-permission}

注入 `AuthorizationChecker` Bean（只要启用了授权就会注册）。像描述它所代表的请求一样，用 `CheckRequest` 描述一次检查：

| 字段 | 默认值 | 含义 |
|---|---|---|
| `path`、`method` | method 为 `GET` | 这次检查代表的请求。 |
| `appId`、`spaceId` | 当前请求的值 | 其[角色授权](../policies/role-permissions.md#role-grant-scope)生效的应用和空间。 |
| `headers` | 无 | 条件以 `request.header.<name>` 读取的请求头。 |
| `attributes` | 无 | 条件以 `request.attributes.<name>` 读取的属性。 |
| `resource` | 无 | 动作目标对象的属性；条件以 `resource.<name>` 读取。 |

检查还会沿用当前请求的客户端 IP。调用 `check(context, request)` 得到一个结果，或调用 `checkAll(context, requests)` 得到一组
结果；两者都返回 `Mono`。`check(context, path, method)` 是简写形式。

```kotlin
@RestController
class PermissionController(private val checker: AuthorizationChecker) {
    // WebFlux；Spring MVC 中用 SecurityContextHolder.requiredContext 代替 exchange
    @PostMapping("/permissions/check")
    fun check(@RequestBody actions: Map<String, CheckRequest>, exchange: ServerWebExchange): Mono<Map<String, Boolean>> =
        checker.checkAll(exchange.getSecurityContext()!!, actions)
}
```

界面提交 `{"edit": {"path": "/orders/42", "method": "PUT"}, "delete": {"path": "/orders/42", "method": "DELETE"}}`，
得到 `{"edit": true, "delete": false}`。这个接口本身需要一条允许已认证调用方访问的策略。

在请求之外（例如消息消费者中），自己为用户构建上下文：`SimpleSecurityContext(principal)`。这时没有当前请求可以提供默认值：
要显式指定应用和空间；另外客户端 IP 是 `127.0.0.1`，所以信任本地地址的策略也会信任这次检查。

### 检查与请求管道的关系 {#how-a-check-relates-to-the-request-pipeline}

检查使用的策略、角色授权、黑名单和根主体规则与真实请求相同，[求值顺序](../guide/security-model.md#evaluation-order)也相同。
区别在于决策之外的部分：

| | 经过适配器的请求 | `AuthorizationChecker` |
|---|---|---|
| 用什么决策 | 完整的链：链路追踪、审计、指标、限流、影子模式，以及你的 `CoSecAuthorizationCustomizer` | 只有 `SimpleAuthorization` |
| 审计事件、链路、指标 | 有 | 无 |
| 限流 | 消耗许可，可能返回 `429` | 从不消耗，从不受限 |
| 安全上下文 | 请求自己的 | 一份副本（只有主体和租户），标记为策略求值 |
| 出错时 | `500` | `false`，并在日志中记一条警告 |
| 结果 | HTTP 状态码 | `true` 或 `false` |

由于每次检查都在自己的安全上下文副本上运行，检查捕获的路径变量不会泄漏到调用方的上下文中；自定义匹配器可以通过
`securityContext.isPolicyEvaluation` 识别检查并跳过副作用。

## 对象级规则 {#object-level-rules}

用户能否修改*这个*订单，取决于订单本身：它的所有者、租户或状态。这些数据在你的数据库里，而适配器在处理器加载任何东西之前就运行了，
所以对象级规则分两步判定：

1. **在适配器中**，路由照常授权。此时还不知道资源，所以所有 `resource.<name>` 部分都为空。
2. **在你的代码中**，加载对象之后，用一次检查把它的属性作为 `resource` 传入，条件以 `resource.<name>` 读取。

把对象级规则写成**只在资源已知时才生效的拒绝**。这样同一个路径能通过适配器，而在检查中对所有者以外的人拒绝：

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

需要资源部分的允许会在适配器中拒绝这个路由，因为那里的资源部分为空：允许只依据请求本身携带的内容，对象条件放在拒绝上。检查必须在
你的代码中执行；跳过检查的处理器不受这条规则保护。条件和模板见[条件](../policies/language.md#conditions)和
[模板](../policies/language.md#templates)。

## 解释一个决策 {#explaining-a-decision}

要查看某个主体对某个请求为什么得到某个决策，而不发出真实请求，可以使用解释器。它用和真实请求相同的代码、针对仓库的一份快照做出
决策，然后遍历每条策略语句和角色权限，说明哪些匹配了，哪些在动作上未匹配，哪些在条件上未匹配。它不消耗也不修改任何东西：限流
规则只会被列出，自定义匹配器看到的 `securityContext.isPolicyEvaluation` 为真。

解释器默认关闭，因为解释结果会暴露你的策略结构。用 `cosec.explain.enabled=true` 启用它。使用 Spring Boot Actuator 时，还要暴露这个端点：

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

这个端点和其他请求一样要经过 CoSec：添加一条策略，只允许你的管理员调用 `POST /actuator/cosecExplain`。

```bash
curl -X POST localhost:8080/actuator/cosecExplain -H 'Content-Type: application/json' -d '{
  "principal": { "id": "user-1", "tenantId": "t1", "roles": ["clerk"], "policies": ["orders"] },
  "request":   { "path": "/orders/42", "method": "DELETE", "appId": "shop" },
  "exhaustive": false,
  "detail": "summary"
}'
```

**输入。** `principal` 包含 `id`、`tenantId`、`roles`、`policies` 和 `attributes`。`request` 包含 `path`、`method`
（`GET`）、`remoteIp`（`127.0.0.1`）、`appId`、`spaceId`、`deviceId`、`origin`、`referer`、`headers`、`query`、`cookies`
和 `attributes`。要解释一次对象级检查，把资源作为名为 `resource.<name>` 的属性传入，例如
`"attributes": { "resource.ownerId": "user-2" }`。`exhaustive: true` 会在得出决策后继续求值后面的层；`detail: "full"`
会附上每个匹配器的配置。

要解释真实调用方得到的结果，省略 `principal`，把他的凭证放进请求头，例如 `"headers": { "Authorization": "Bearer eyJ..." }`
或 `{ "X-API-Key": "..." }`。凭证由应用自己的安全上下文解析器读取，包括吊销检查和 API key。被拒绝的凭证按它所变成的匿名请求来解释，
带有真实的 `401` 原因和一条 `TOKEN_INVALID` 警告。

**输出。**

| 字段 | 含义 |
|---|---|
| `status`、`authorized`、`reason` | 决策，[按真实请求的方式](./web.md#responses-and-status-codes)映射为状态码，只是限流永远不会让它变成 `429`。 |
| `decidedBy` | 做出决定的规则：层、策略 ID 和语句下标，或者角色和权限 ID。没有任何匹配时为 `null`。 |
| `layers` | `BLACKLIST`、`ROOT`、`GLOBAL_POLICY`、`PRINCIPAL_POLICY`、`ROLE_PERMISSION`，每层的状态为 `EVALUATED`、`NOT_EVALUATED`（之前的显式拒绝结束了求值）、`EMPTY` 或 `EVALUATED_AFTER_DECISION`（`exhaustive: true` 时）。每条语句和权限都有一个结果：`MATCHED`、`ACTION_MISS`、`CONDITION_MISS`、`NOT_EVALUATED`（它所在策略或应用的条件不匹配）或 `ERROR`。 |
| `overriddenAllows` | 被之后的显式拒绝推翻的允许。 |
| `rateLimitRules` | 如果请求被允许，它会消耗许可的[限流规则](../policies/rate-limiting.md)。 |
| `warnings` | `MISSING_POLICY`（主体列出了仓库中没有的策略，真实请求会得到 `500`）、`NO_APP_PERMISSION`（空间中有角色，但应用没有应用权限）、`NO_ROLE_IN_SPACE`（有角色，但没有写给请求所在空间的角色；见[角色与空间](./authentication.md#roles-and-spaces)）、`MATCHER_ERROR`、`TOKEN_INVALID`（凭证被拒绝），以及 `TRACE_INCONSISTENT`（遍历结果与决策不一致：以决策为准，并作为缺陷报告）。 |

没有 Actuator 时，注入 `AuthorizationExplainer` Bean，调用 `explain(request, principal)`（或用 `explain(request)` 使用请求自带的
凭证），例如在一个固定"谁能做什么"的测试中。针对策略文件的测试用例，见[测试策略](../policies/testing.md)。

**下一步：** 每个属性及其默认值，见[配置](../operations/configuration.md)；要编写规则本身，见[策略语言](../policies/language.md)。

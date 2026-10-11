# 策略 {#policies}

策略是一份授予或拒绝访问的 JSON 文档。本页是策略语言的参考：结构、动作、条件、策略如何组合，以及基于角色的应用权限。

供编辑器和 CI 使用的 JSON Schema 位于 [`schema/`](https://github.com/Ahoo-Wang/CoSec/tree/main/schema)：
`cosec-policy.schema.json` 和 `cosec-app-permission.schema.json`。

## 结构 {#structure}

```json
{
  "id": "order-service",
  "name": "Order service",
  "category": "order",
  "description": "Who may read and change orders",
  "type": "custom",
  "tenantId": "(platform)",
  "condition": { "authenticated": {} },
  "statements": [
    { "name": "read", "action": { "path": { "pattern": "/orders/**", "method": "GET" } } },
    { "name": "no-delete", "effect": "deny", "action": { "path": { "pattern": "/orders/*", "method": "DELETE" } } }
  ]
}
```

| 字段 | 必填 | 含义 |
|---|---|---|
| `id` | 是 | 策略的唯一 id。主体通过它引用非全局策略。 |
| `name`、`category`、`description` | 是 | 描述性元数据。 |
| `type` | 是 | `global`：对每个请求都求值。`system` / `custom`：只对 `policies` 中包含该 id 的主体求值。 |
| `tenantId` | 是 | 所属租户（平台级策略为 `(platform)`）。 |
| `condition` | 否 | 策略级条件。不匹配时整条策略被跳过。 |
| `statements` | 是 | 规则列表。 |

枚举值（`type`、`effect`）不区分大小写。

### 语句 {#statement}

| 字段 | 必填 | 默认值 | 含义 |
|---|---|---|---|
| `name` | 否 | `""` | 显示在日志、链路追踪和审计事件中。 |
| `effect` | 否 | `allow` | `allow` 或 `deny`。 |
| `action` | 是 | | 语句覆盖哪些请求。 |
| `condition` | 否 | 始终 | 语句何时生效。 |

动作和条件都匹配时语句才算**命中**。命中的 `allow` 得到 `ALLOW`，命中的 `deny` 得到 `EXPLICIT_DENY`。

## 动作 {#actions}

动作用于选择请求，通常按路径选择。有四种写法：

| 写法 | 示例 | 含义 |
|---|---|---|
| `"*"` | `"action": "*"` | 所有请求。 |
| 字符串 | `"action": "/orders/{id}"` | 一个路径模式，任意方法。 |
| 数组 | `"action": ["/a/**", "/b"]` | 多个动作中任意一个；元素可以是字符串或对象。 |
| 对象 | `"action": { "path": { ... } }` | 带选项的、指定类型的动作匹配器。 |

`path` 动作匹配器：

```json
{
  "path": {
    "pattern": ["/orders/{id}", "/orders/{id}/items"],
    "method": ["GET", "HEAD"],
    "options": { "caseSensitive": true, "separator": "/", "decodeAndParseSegments": true }
  }
}
```

- `pattern`：一个或一组 Spring [`PathPattern`](https://docs.spring.io/spring-framework/reference/web/webflux/controller/ann-requestmapping.html#webflux-ann-requestmapping-uri-templates)
  （`*`、`**`、`{var}`、`{*rest}`）。
- `method`：可选；一个或一组 HTTP 方法。省略表示任意方法。
- `options`：可选；路径如何切分和比较。借助分隔符也可以匹配非 HTTP 的“路径”，例如 `"separator": "."` 时的 `order.read`。

**路径变量。** 命中的动作捕获的变量，可以在条件中以 `request.path.var.<name>` 读取。

**模板模式。** 包含 `#{...}` 的模式是 SpEL 模板，会针对安全上下文求值，因此模式可以随调用方变化。
例如 `"/users/#{principal.id}/**"` 让每个用户只能访问自己的资源。

## 条件 {#conditions}

条件是只有一个键的对象：键是条件类型，值是它的配置。

```json
"condition": { "eq": { "part": "context.tenantId", "value": "(platform)" } }
```

所有条件都支持 `"negate": true`，用于取反结果。

### 上下文条件 {#context-conditions}

| 类型 | 配置 | 何时匹配 |
|---|---|---|
| `all` | `{}` | 始终 |
| `authenticated` | `{}` | 主体不是匿名的 |
| `inRole` | `{ "value": "admin" }` | 主体拥有该角色 |
| `inTenant` | `{ "value": "default" \| "user" \| "platform" }` | 租户是默认租户 `(0)`、用户租户或平台租户 `(platform)` |

### 取值条件 {#value-conditions}

这类条件读取请求或安全上下文的某个**部分**（part），再进行比较。

| 类型 | 配置 | 当该部分…时匹配 |
|---|---|---|
| `eq` | `part`、`value`、`ignoreCase` | 等于 `value` |
| `in` | `part`、`value`（数组） | 是 `value` 之一 |
| `contains` | `part`、`value`、`ignoreCase` | 包含 `value` |
| `startsWith` | `part`、`value`、`ignoreCase` | 以 `value` 开头 |
| `endsWith` | `part`、`value`、`ignoreCase` | 以 `value` 结尾 |
| `regular` | `part`、`pattern`、`timeout`（毫秒，默认 `1000`） | 匹配该正则表达式 |
| `path` | `part`、`pattern`、`options` | 匹配该路径模式 |

`regular` 在时间预算内执行，以防范 ReDoS。超时会拒绝请求（`403`），绝不会被当作匹配。

**可用的部分（part）：**

| Part | 取值 |
|---|---|
| `request.path`、`request.method`、`request.remoteIp` | 路径、HTTP 方法、客户端 IP（见[客户端 IP](./configuration.md#authorization)） |
| `request.appId`、`request.spaceId`、`request.deviceId` | `CoSec-App-Id`、`CoSec-Space-Id`、`CoSec-Device-Id` 请求头（或查询参数） |
| `request.origin`、`request.origin.host`、`request.referer`、`request.referer.host` | `Origin` / `Referer` 及其主机名 |
| `request.header.<name>` | 任意请求头 |
| `request.attributes.<name>` | 请求属性，例如来自 [IP 地理位置](./operations.md#ip-geolocation) 的 `ipRegion`，或网关路由 id |
| `request.path.var.<name>` | 命中的动作捕获的路径变量 |
| `context.tenantId`、`context.principal.id` | 租户 id 和主体 id |
| `context.principal.attributes.<name>` | 主体属性（JWT `attributes` 声明中的一项） |

### 组合条件 {#composing-conditions}

`bool` 用于组合条件：`and` 中的条件必须全部匹配；如果有 `or`，其中至少一个也必须匹配。

```json
"condition": {
  "bool": {
    "and": [ { "authenticated": {} } ],
    "or":  [ { "inRole": { "value": "admin" } },
             { "eq": { "part": "request.header.X-Team", "value": "ops" } } ]
  }
}
```

### 表达式 {#expressions}

| 类型 | 配置 | 说明 |
|---|---|---|
| `spel` | `expression` | Spring 表达式语言，只读；根对象提供 `request` 和 `context`。 |
| `ognl` | `expression` | 运行在沙箱中的 OGNL：只能只读地访问 `request` 和 `context`；不能调用修改方法、使用反射、构造对象或引用类型。 |

优先使用上面的声明式条件。表达式更难审查和测试。

### 限流 {#rate-limits}

限流条件在还有许可时匹配。许可用尽时，请求以 `429 Too Many Requests` 被拒绝。

| 类型 | 配置 | 作用范围 |
|---|---|---|
| `rateLimiter` | `permitsPerSecond` | 每个应用实例 |
| `groupedRateLimiter` | `part`、`permitsPerSecond`、`expireAfterAccessSecond` | 每个实例，`part` 的每个取值各一个限流器（例如按用户） |
| `redisRateLimiter` | `permitsPerSecond`、`windowSeconds`（默认 `1`）、`strictFailure`（默认 `false`） | 全集群（Redis，滑动窗口） |
| `redisGroupedRateLimiter` | 以上配置，再加 `part` | 全集群，按 `part` 的取值分别限流 |

Redis 限流器需要 `cache-support`。`strictFailure: false` 时，Redis 故障会放行请求，而不是拦截所有流量；
设为 `true` 则出错即拒绝。

```json
{ "name": "login-throttle",
  "action": { "path": { "pattern": "/auth/login", "method": "POST" } },
  "condition": { "redisGroupedRateLimiter": { "part": "request.remoteIp", "permitsPerSecond": 5 } } }
```

## 策略如何组合 {#how-policies-combine}

对每个请求，CoSec 依次求值三层：

1. **全局策略**：所有 `global` 策略。
2. **主体策略**：主体 `policies` 中列出的策略。
3. **角色权限**：按请求的应用和空间，授予主体角色的权限。

在每一层内部以及各层之间，都是**拒绝优先**：只要有任何命中的语句或权限是 `deny`，结果就是
`EXPLICIT_DENY`，即使另一层允许了该请求。否则只要命中任意 `allow` 就放行。什么都没命中时，结果是
`IMPLICIT_DENY`。

有两种情况绕过上述流程：**root** 主体（id 为 `cosec`，可配置，见[配置](./configuration.md#authorization)）
始终放行；在黑名单中的请求始终拒绝。

::: tip 策略级条件最先执行
策略自身的 `condition` 在它的语句之前、每个请求只求值一次。放在这里的限流条件，会对该策略作用的每个请求消耗一个许可，无论最终由哪条语句做出决定。
:::

## 应用权限与角色 {#app-permissions-and-roles}

策略把规则直接附加给主体。要做基于角色的访问控制，可以先描述一次应用的权限，再把它们授予角色。

```json
{
  "id": "order-app",
  "condition": { "authenticated": {} },
  "groups": [
    {
      "name": "orders",
      "description": "Order management",
      "permissions": [
        { "id": "order.read",   "name": "Read orders",   "action": { "path": { "pattern": "/orders/**", "method": "GET" } } },
        { "id": "order.manage", "name": "Manage orders", "action": "/orders/**" },
        { "id": "order.export.block", "name": "No exports", "effect": "deny", "action": "/orders/export" }
      ]
    }
  ]
}
```

- 权限就是带 `id`（以及可选的 `name`、`description`）的语句。
- 应用的 `condition` 决定它的所有权限是否生效。
- 请求通过 `CoSec-App-Id` 请求头选择应用，通过 `CoSec-Space-Id` 选择空间。
- 角色按空间被授予**一组权限 id**；`*` 表示授予该应用的全部权限。

使用 `cache-support` 时，CoSec 从 Redis 读取这些数据（默认键前缀为 `cosec`）：

| 数据 | Redis 键 | 值 |
|---|---|---|
| 应用权限 | `cosec:app:permission:<appId>` | 上面的 JSON |
| 角色授权 | `cosec:role:permission:<roleId>`（默认空间）或 `cosec:role:permission:<roleId>@<spaceId>` | 权限 id 的集合 |

CoSec 只读取权限和授权，写入由你的管理服务负责。

## 加载与生成策略 {#loading-and-generating-policies}

- **本地文件**：`cosec.authorization.local-policy.enabled=true` 时，从 `classpath:cosec-policy/*-policy.json`
  加载策略。`init-repository=true` 时在启动时写入 `PolicyRepository`（已存在的 id 会保留，除非 `force-refresh=true`）。
- **运行时**：通过 `PolicyRepository.setPolicy` 写入策略。基于 Redis 的仓储会在存储前校验策略，所有实例都会立即看到变更。
- **基于 OpenAPI**：使用 `openapi-support` 时，actuator 端点 `cosecPolicyGenerator` 和 `cosecAppPermissionGenerator`
  会根据你的 API 描述生成一份策略或应用权限作为起点。详见[运维](./operations.md#openapi)。

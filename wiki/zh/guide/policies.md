# 策略 {#policies}

策略是一份授予或拒绝访问的 JSON 文档。本页是策略语言的参考：结构、动作、条件、策略如何组合、基于角色的应用权限，
以及策略如何加载和校验。

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
| `name` | 是 | 显示名称。 |
| `type` | 是 | `global`：对每个请求都求值。`system` / `custom`：只对 `policies` 中包含该 id 的主体求值。 |
| `tenantId` | 是 | 所属租户（平台级策略为 `(platform)`）。 |
| `category`、`description` | 否 | 描述性元数据。 |
| `condition` | 否 | 策略级条件。不匹配时整条策略被跳过。 |
| `statements` | 否 | 规则列表。没有语句的策略永远不会命中。 |

枚举值（`type`、`effect`）不区分大小写。

### 语句 {#statement}

| 字段 | 必填 | 默认值 | 含义 |
|---|---|---|---|
| `name` | 否 | `""` | 显示在日志、链路追踪和审计事件中。 |
| `effect` | 否 | `allow` | `allow` 或 `deny`。 |
| `action` | 是 | | 语句覆盖哪些请求。 |
| `condition` | 否 | 始终 | 语句何时生效。 |

先匹配动作、再匹配条件，两者都匹配时语句才算**命中**。命中的 `allow` 得到 `ALLOW`，命中的 `deny` 得到 `EXPLICIT_DENY`。

## 动作 {#actions}

动作用于选择请求，通常按路径选择。有以下写法：

| 写法 | 示例 | 含义 |
|---|---|---|
| `"*"` | `"action": "*"` | 所有请求。 |
| 字符串 | `"action": "/orders/{id}"` | 一个路径模式，任意方法。 |
| 数组 | `"action": ["/a/**", { "path": { ... } }]` | 多个动作中任意一个；元素是字符串或对象。 |
| `path` | `"action": { "path": { ... } }` | 带方法和选项的路径模式（见下文）。 |
| `all` | `"action": { "all": { "method": "GET" } }` | 所有路径，可限定部分方法。 |
| `composite` | `"action": { "composite": [ ... ] }` | 与数组写法相同。 |

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
- `method`：可选；一个或一组 HTTP 方法，不区分大小写。省略表示任意方法。
- `options`：可选；路径如何切分和比较。借助分隔符也可以匹配非 HTTP 的“路径”，例如 `"separator": "."` 时的 `order.read`。

**路径变量。** 命中的动作捕获的变量，可以在该语句的条件中以 `request.path.var.<name>` 读取。

## 模板 {#templates}

包含 `#{...}` 的模式或取值是只读的 SpEL 模板，会针对安全上下文求值，因此规则可以随调用方变化。模板的根对象提供
`principal`（含 `id`、`roles`、`attributes`）和 `tenant`（含 `tenantId`）。

模板可用于两处：

- **路径模式**：`"/users/#{principal.id}/**"` 让每个用户只能访问自己的资源；
- **`eq` 条件的 `value`**：把请求的某个部分与调用方比较，如下所示。

```json
{ "name": "own-orders",
  "action": "/users/{userId}/orders/**",
  "condition": { "eq": { "part": "request.path.var.userId", "value": "#{principal.id}" } } }
```

### 路径模式中的模板值 {#template-values-in-path-patterns}

在路径模式中，每个 `#{...}` 单独求值，它的值只填充模式中的一段字面量；模式的其余部分保持原样。值为空（或不存在）、
为 `.` 或 `..`，或含有 `*`、`?`、`{`、`}` 或路径分隔符（`/`，或 `separator` 选项）时会被拒绝，否则它会被当作模式语法，
或伸进其他路径段。放在 `{...}` 捕获内部的 `#{...}`（例如 `{name:#{...}}`）会在加载策略时失败。被拒绝的值不会扩大访问范围，
也不会让拒绝失效：

| 动作所在位置 | 被拒绝的值 |
|---|---|
| `allow` 语句或权限 | 不匹配：请求以 `Implicit Deny` 结束，不是错误 |
| `deny` 语句或权限 | 匹配所有路径，所以只要该策略（或应用权限）适用，这个主体就被拒绝 |
| 限流规则 | 匹配所有路径，所以这个主体逃不出限流 |
| 用 `PathActionMatcherFactory` 在代码中构建、不在语句中使用的匹配器 | 抛出 `RejectedTemplateValueException`，请求以错误失败关闭 |

要匹配可能含有这些字符的 id 或属性，请使用上面的静态模式加 `eq`；`eq` 按字符串相等比较。

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

`inTenant` 判断的是租户的*种类*。要判断具体的租户 id，请使用
`{ "eq": { "part": "context.tenantId", "value": "..." } }`。

### 时间条件 {#time-conditions}

`time` 在有效期内、以及可选的每日周期时间窗内匹配。它读取服务器时钟，从不读取请求中的时间。

| 配置 | 含义 |
|---|---|
| `notBefore`、`notAfter` | 有效期，取值为 ISO 日期时间；`notAfter` 不包含在内。不带时区偏移的日期时间按 `zone` 解读。 |
| `daysOfWeek` | 时间窗生效的星期，例如 `["MON", "TUE", "WED", "THU", "FRI"]`（也可以写全称）。 |
| `from`、`to` | 每日时间窗 `[from, to)`，格式为 `HH:mm`。`from` 晚于 `to` 时时间窗跨越午夜，凌晨的部分属于它开始的那一天。 |
| `zone` | 时间窗以及不带偏移的日期时间所用的时区，默认 `UTC`。 |

有效期、`daysOfWeek`、`from`/`to` 至少要设置一项。需要到期自动失效的授权，就是一条带 `notAfter` 的语句：

```json
{ "name": "temporary-support-access",
  "action": "/orders/**",
  "condition": { "bool": { "and": [
    { "inRole": { "value": "support" } },
    { "time": { "notAfter": "2026-10-20T00:00:00", "zone": "Asia/Shanghai" } } ] } } }
```

`negate` 可以把时间窗变成“时间窗之外”，例如在工作时间之外拒绝写操作。

### 取值条件 {#value-conditions}

这类条件读取请求或安全上下文的某个**部分**（part），再进行比较。

| 类型 | 配置 | 当该部分…时匹配 |
|---|---|---|
| `eq` | `part`、`value`（可用[模板](#templates)）、`ignoreCase` | 等于 `value` |
| `in` | `part`、`value`（数组） | 是 `value` 之一 |
| `contains` | `part`、`value`、`ignoreCase` | 包含 `value` |
| `startsWith` | `part`、`value`、`ignoreCase` | 以 `value` 开头 |
| `endsWith` | `part`、`value`、`ignoreCase` | 以 `value` 结尾 |
| `regular` | `part`、`pattern`、`timeout`（毫秒，默认 `1000`） | 整体匹配该正则表达式（不区分大小写） |
| `path` | `part`、`pattern`、`options` | 匹配该路径模式 |

`ignoreCase` 默认为 `false`。`regular` 在时间预算内执行，以防范 ReDoS。超时会拒绝请求（`403`），绝不会被当作匹配。

**可用的部分（part）：**

| Part | 取值 |
|---|---|
| `request.path`、`request.method`、`request.remoteIp` | 路径、HTTP 方法、客户端 IP（见[客户端 IP](./web-integration.md#client-ip-behind-proxies)） |
| `request.appId`、`request.spaceId`、`request.deviceId` | `CoSec-App-Id`、`CoSec-Space-Id`、`CoSec-Device-Id` 请求头（或查询参数） |
| `request.origin`、`request.origin.host`、`request.referer`、`request.referer.host` | `Origin` / `Referer` 及其主机名 |
| `request.header.<name>` | 任意请求头 |
| `request.attributes.<name>` | [请求属性](./web-integration.md#request-attributes)，例如 `ipRegion` |
| `resource.<name>` | 目标对象的属性，由代码中的检查设置；在请求过滤器中为空（见[对象级规则](#object-level-rules)） |
| `request.path.var.<name>` | 命中的动作捕获的路径变量 |
| `context.tenantId`、`context.principal.id` | 租户 id 和主体 id |
| `context.principal.attributes.<name>` | 主体属性（JWT `attributes` 声明中的一项） |

请求中不存在的部分读作空字符串。其他任何 part 名称都是策略错误：加载策略时不会被发现，
每个执行到该条件的请求都会以 `500` 失败。

请求头、`request.remoteIp` 和请求属性的可信度取决于它们的来源。除非有可信代理覆盖该请求头，
否则不要基于客户端可以自行设置的请求头做决策。

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

| 类型 | 配置 | 求值对象 |
|---|---|---|
| `spel` | `expression` | Spring 表达式语言，只读。根对象有 `request` 和 `context` 两个属性，例如 `context.principal.attributes['tier'] == 'gold'`。 |
| `ognl` | `expression` | 运行在沙箱中的 OGNL。根对象是请求；安全上下文是变量 `#context`（请求也可用 `#request` 访问）。不能调用修改方法、使用反射、构造对象或引用类型。 |

优先使用上面的声明式条件。表达式更难审查和测试。

::: tip 限流不是条件
策略决定请求是否被允许。被允许的请求可以多频繁地通过，由独立的[限流规则](./rate-limiting.md)决定。
:::

## 策略如何组合 {#how-policies-combine}

对每个请求，CoSec 依次求值三层：

1. **全局策略**：所有 `global` 策略。
2. **主体策略**：主体 `policies` 中列出的策略。
3. **角色权限**：按请求的应用和空间，授予主体角色的权限。

在每一层内部以及各层之间，都是**拒绝优先**：只要有任何命中的语句或权限是 `deny`，结果就是
`EXPLICIT_DENY`，即使另一层允许了该请求。否则只要命中任意 `allow` 就放行。什么都没命中时，结果是
`IMPLICIT_DENY`。策略和语句的顺序从不改变结果，只影响报告的是哪条命中规则。

在这三层之前还有两项检查：**root** 主体（id 为 `cosec`，可配置，见[配置](./configuration.md#authorization)）
始终放行；被黑名单拦截的请求始终拒绝。

策略自身的 `condition` 在它的语句之前、每个请求只求值一次。

## 对象级规则 {#object-level-rules}

用户能否修改*这一张*订单取决于订单本身：它的属主、租户或状态。这些数据在你的数据库里，而请求过滤器在处理器加载任何数据之前就已运行，
因此 CoSec 分两步判断对象级规则：

1. **在请求过滤器中**照常对路由授权。此时资源未知，所有 `resource.<name>` 部件都为空。
2. **在你的代码中**加载对象之后，用 [`AuthorizationChecker`](./web-integration.md#checking-permissions-in-code)
   做一次检查，把对象的属性作为 `resource` 传入，条件以 `resource.<name>` 读取它们。

把对象规则写成**仅在资源已知时生效的拒绝**。这样同一条路径能通过过滤器，而在检查中对属主以外的人拒绝：

```json
{ "name": "only-own-orders",
  "effect": "deny",
  "action": "/orders/**",
  "condition": { "bool": { "and": [
    { "eq": { "part": "resource.ownerId", "value": "", "negate": true } },
    { "eq": { "part": "resource.ownerId", "value": "#{principal.id}", "negate": true } } ] } } }
```

```kotlin
val order = orderRepository.findById(id)
val allowed = checker.check(
    securityContext,
    CheckRequest("/orders/$id", "PUT", resource = mapOf("ownerId" to order.ownerId)),
).awaitSingle()
if (!allowed) throw AccessDeniedException()
```

需要资源部件的 `allow` 会在过滤器中拒绝该路由，因为那时部件为空。请让放行只依赖请求本身携带的信息，把对象条件放在拒绝上。
检查必须在你的代码中执行：跳过检查的处理器不受对象规则保护。

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
  授权只读取仓储，所以没有 `init-repository`，本地文件不会生效。
- **运行时**：通过 `PolicyRepository.setPolicy` 写入策略。基于 Redis 的仓储会在存储前校验策略，所有实例都会立即看到变更。
- **基于 OpenAPI**：使用 `openapi-support` 和 Spring Boot Actuator 时，`GET /actuator/cosecPolicyGenerator`
  生成一份策略，`GET /actuator/cosecAppPermissionGenerator` 生成一份应用权限：每个接口（路径和方法）一条 `allow`，
  id 和元数据为占位值。请把它们当作起点：设置 id、收窄规则，审查后再上线。

::: warning 无效文件会被跳过
无法解析的本地策略文件（JSON 无效、缺少必填字段、未知的条件类型、匹配器配置错误）会以 `ERROR` 级别记录日志并被跳过，
应用仍会正常启动。请检查启动日志，或按下文在测试中校验策略。
:::

### 策略历史与回滚 {#policy-history-and-rollback}

Redis 中只保存每条策略的当前版本。设置 `cosec.authorization.cache.history.enabled=true` 后，每次通过仓储保存策略时都会额外记录一个修订版本：
递增的版本号、时间、作者、说明以及完整的策略内容，按从新到旧排列，每条策略最多保留 `max-revisions` 个。作者通过 Reactor Context 传入：

```kotlin
policyRepository.setPolicy(policy)
    .contextWrite(PolicyRevisions.author("alice", "TICKET-42: allow exports"))
```

`PolicyHistory` bean 用于读取和恢复修订版本。回滚会把旧内容保存为一个新的修订版本，因此历史只增不减，并会记录是谁执行了回滚：

```kotlin
policyHistory.history("orders")                 // 从新到旧
policyHistory.rollback("orders", revision = 3, author = "bob")
```

没有提供作者的保存记录为 `unknown`。策略先写入、再追加修订版本，因此历史中不会出现未生效的版本。

## 校验策略 {#validating-policies}

在单元测试中用运行时解析器校验策略，而不只是 JSON Schema：

```kotlin
val policies = LocalPolicyLoader(setOf("classpath:cosec-policy/*-policy.json")).policies
check(policies.any { it.id == "order-service" })   // the file was not skipped
policies.forEach(DefaultPolicyEvaluator::evaluate)  // matchers run against a sample request
```

`DefaultPolicyEvaluator` 会用一个示例请求把每个条件和动作执行一遍，因此只在请求时才暴露的配置错误（例如未知的 part）
会让测试失败。对每个重要的允许/拒绝边界，再用 `SimpleAuthorization` 搭配内存仓储写一个授权测试。

## 用影子模式试运行变更 {#trying-changes-in-shadow-mode}

测试只能告诉你策略变更对你想到的那些请求有什么影响，影子模式则能在变更生效之前，告诉你它对真实流量有什么影响。
把策略的候选版本放进影子文件后，CoSec 会对每个被采样的请求评估两次：一次使用生效中的策略，返回的就是这次的决策；
另一次使用叠加了候选版本的策略。两次结果不一致的请求都会被上报。

```yaml
cosec:
  shadow:
    enabled: true
    locations: classpath:cosec-shadow/*-policy.json   # 默认值
    sample-rate: 0.1
```

一个影子文件包含一条策略，格式与本地策略文件相同。候选策略会在其 `id` 对应的生效策略适用的所有地方替换它。
`id` 为新值的候选策略会被新增：类型为 `global` 时作为全局策略，否则作用于列出它的主体。要试运行删除某条策略，
可以给它一个没有语句的候选版本。

应用权限也可以试运行：匹配 `classpath:cosec-shadow/*-app-permission.json` 的文件（每个文件一份应用权限）会替换应用 `id`
相同的生效应用权限，包括其权限目录和条件。角色授权保持生效中的版本，因此授权中引用了被候选版本删除的权限时，这部分授权不再生效。
角色授权本身不参与影子评估。

| 差异 | 含义 |
|---|---|
| `WIDENED` | 现在被拒绝、候选策略会放行：这次变更授予了访问权限。正式生效前请逐一审查。 |
| `NARROWED` | 现在被放行、候选策略会拒绝：这次变更收回了访问权限。 |
| `CANDIDATE_ERROR` | 候选策略求值失败，例如匹配器抛出异常。正式生效后会以错误拒绝请求。 |
| `RULE_CHANGED` | 结果相同，但由另一条规则做出决定。仅在设置 `report-rule-changes: true` 时上报。 |

每条差异都包含请求信息（id、租户、主体、应用、方法、路径）以及两次评估的结果和做出决定的规则。默认情况下差异以
`me.ahoo.cosec.shadow` 记录日志：访问权限变化为 `WARN`，规则变化为 `INFO`。声明 `ShadowEventSink` bean 可以把差异发送到别处，
声明 `ShadowPolicyProvider` bean 可以从文件以外的地方提供候选策略。

影子模式从不改变响应，它的错误也只会被记录。但每个被采样的请求都要多评估一次，因此在繁忙的服务上请把 `sample-rate`
设得低一些。候选策略在一个单独的、被标记为策略评估的安全上下文中运行，自定义匹配器可以据此跳过副作用，也不会消耗限流许可。
无法读取的影子文件会阻止应用启动，因为被漏掉的候选策略会对一个从未评估过的变更报告“没有差异”。差异符合预期后，
再把候选策略移入生效的策略中。

### 策略测试用例 {#policy-test-cases}

把你依赖的决策写成数据，并在 CI 中运行。测试套件是一个 JSON 文件，指明所用的策略（以及可选的应用权限和角色授权），
并列出用例：一个主体、一个请求和期望的结果，还可以指定必须由哪条规则做出决定。

```json
{
  "name": "orders",
  "policies": ["classpath:cosec-policy/*-policy.json"],
  "appPermissions": ["classpath:cosec-policy/*-app-permission.json"],
  "roles": { "shipper": ["manage.order.ship"] },
  "cases": [
    { "name": "users read orders",
      "principal": { "id": "alice" },
      "request": { "path": "/orders/42" },
      "expect": { "result": "ALLOW", "policyId": "orders", "statementName": "read-orders" } },
    { "name": "internal orders are denied",
      "principal": { "id": "alice" },
      "request": { "path": "/orders/internal/1" },
      "expect": { "result": "EXPLICIT_DENY" } }
  ]
}
```

```kotlin
@Test
fun policies() = PolicyTestKit.run("classpath:cosec-policy-test/*.json").assertPasses()
```

`principal` 和 `request` 的字段与[解释一次决策](./troubleshooting.md#explain-a-decision)相同，`expect.result` 取值为
`ALLOW`、`EXPLICIT_DENY` 或 `IMPLICIT_DENY`。每个用例都通过解释器在内存仓储上运行，因此失败时会指出实际做出决定的规则以及所有警告，
例如主体列出了某个策略 id 却没有文件定义它。无法读取的策略文件会让本次运行失败，而不是被跳过。

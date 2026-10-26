---
title: "策略语言"
description: "CoSec 策略的每个字段：语句、动作、路径变量、模板、条件和表达式，以及策略文件如何加载。"
---

# 策略语言 {#policy-language}

面向需要确切了解某个字段含义的策略作者。本页是策略 JSON 格式的参考。策略之间如何组合在[安全模型](../guide/security-model.md#evaluation-order)中讲解，
这里只用一句话概括：任何地方的显式拒绝优先，否则任一允许即放行，什么都没匹配则拒绝。

[应用权限](./role-permissions.md#permission-catalogs)使用同样的语句格式，[限流规则](./rate-limiting.md#rules)的
`action` 和 `condition` 也一样。

## 策略文档 {#policy-document}

```json
{
  "id": "orders",
  "name": "Orders",
  "category": "order",
  "description": "Who may read and change orders",
  "type": "custom",
  "tenantId": "(platform)",
  "condition": { "authenticated": {} },
  "statements": [
    { "name": "read-orders", "action": { "path": { "pattern": "/orders/**", "method": "GET" } } },
    { "name": "no-delete", "effect": "deny", "action": { "path": { "pattern": "/orders/*", "method": "DELETE" } } }
  ]
}
```

| 字段 | 必填 | 含义 |
|---|---|---|
| `id` | 是 | 策略 id。主体通过这个 id 列出非全局策略。在一组策略文件中唯一。不能以 `history:` 开头，Redis 仓储把这个前缀留给策略历史（[Redis key](../operations/storage.md#redis-keys)）。 |
| `name` | 是 | 显示名称。 |
| `type` | 是 | `global`、`system` 或 `custom`，见下表。不区分大小写。 |
| `tenantId` | 是 | 所属租户，例如 `(platform)` 或 `(0)`。只是元数据：授权从不拿它和请求的租户比较。 |
| `category`、`description` | 否 | 元数据。默认 `""`。 |
| `condition` | 否 | 在语句之前求值；不匹配时，整个策略不起作用。默认总是匹配。 |
| `statements` | 否 | 规则。默认 `[]`；没有语句的策略永远不会匹配。 |

| `type` | 对谁求值 |
|---|---|
| `global` | 每个请求。 |
| `system` | `policies` 中列出该 id 的主体。用于平台管理员维护的策略。 |
| `custom` | `policies` 中列出该 id 的主体。用于用户自定义的策略。 |

`system` 和 `custom` 在授权中的行为完全相同，类型只是给维护策略的人看的标签。

::: warning `tenantId` 不隔离租户
任何租户的主体，只要列出了某个策略 id，就会得到这个策略。要把策略限定在某个租户，加一个条件，例如
`{ "eq": { "part": "context.tenantId", "value": "t1" } }`。
:::

## 语句 {#statements}

| 字段 | 必填 | 默认值 | 含义 |
|---|---|---|---|
| `name` | 否 | `""` | 出现在 explain 结果、测试结果、影子差异和审计事件中。要测试的语句都应该命名。 |
| `effect` | 否 | `allow` | `allow` 或 `deny`，不区分大小写。 |
| `action` | 是 | | 语句覆盖哪些请求（[动作](#actions)）。 |
| `condition` | 否 | 总是匹配 | 语句何时生效（[条件](#conditions)）。 |

动作匹配、并且条件随后也匹配时，语句才算匹配。匹配的 `allow` 得到 `ALLOW`，匹配的 `deny` 得到 `EXPLICIT_DENY`。

## 动作 {#actions}

动作按路径和方法选择请求。类型是对象唯一的键；字符串和数组是简写。

| 写法 | 示例 | 含义 |
|---|---|---|
| `"*"` | `"action": "*"` | 所有请求。 |
| 字符串 | `"action": "/orders/{id}"` | 一个 `path` 模式，任意方法。 |
| 数组 | `"action": ["/orders/**", { "path": { "pattern": "/carts/**" } }]` | `composite`：任一元素匹配即匹配。元素可以是字符串或动作对象。 |
| `path` | `"action": { "path": { "pattern": "/orders/**", "method": "GET" } }` | 带方法和选项的路径模式。 |
| `all` | `"action": { "all": { "method": "GET" } }` | 所有路径，可以只限某些方法。 |
| `composite` | `"action": { "composite": ["/a/**", "/b/**"] }` | 同数组写法。 |

`path` 和 `all` 接受 `method`：一个 HTTP 方法或方法数组，比较时不区分大小写。省略表示任意方法。对象有多个键时只用第一个，其余被忽略。

### `path` {#path}

```json
{
  "path": {
    "pattern": ["/orders/{orderId}", "/orders/{orderId}/items"],
    "method": ["GET", "HEAD"],
    "options": { "caseSensitive": false, "separator": "/", "decodeAndParseSegments": true }
  }
}
```

| 选项 | 默认值 | 含义 |
|---|---|---|
| `pattern` | 必填 | 一个 Spring [`PathPattern`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/util/pattern/PathPattern.html) 或其数组：`?`、`*`、`**`、`{var}`、`{var:regex}`、`{*rest}`。数组中任一模式匹配即匹配。模式中可以有[模板](#templates)。 |
| `method` | 任意 | 见上文。 |
| `options.caseSensitive` | `true` | 路径段比较是否区分大小写。 |
| `options.separator` | `/` | 切分路径的单个字符。设为 `"."` 时，可以匹配 `order.read` 这样的“路径”。 |
| `options.decodeAndParseSegments` | `true` | 匹配前解码 `%xx` 并解析 `;` 路径参数。 |

`path` 也可以直接写字符串或数组，而不写对象：`{ "path": "/orders/**" }`。

匹配的 `path` 动作会捕获其中的 `{var}`，供同一条语句的条件读取（[路径变量](#path-variables)）。

### `all` {#all}

`{ "all": {} }` 匹配所有请求；`{ "all": { "method": ["POST", "PUT"] } }` 匹配使用这些方法之一的所有请求。

### `composite` {#composite}

动作数组，元素写成字符串或动作对象；任一元素匹配即匹配。

```json
{ "composite": ["/carts/**", { "path": { "pattern": "/orders/**", "method": "GET" } }] }
```

## 路径变量 {#path-variables}

`path` 动作捕获的变量（如上面的 `{orderId}`）用 part `request.path.var.<name>` 读取，并且只能在同一条语句、权限或限流规则的条件中读取：

```json
{
  "name": "own-orders",
  "action": "/users/{userId}/orders/**",
  "condition": { "eq": { "part": "request.path.var.userId", "value": "#{principal.id}" } }
}
```

每条语句、权限和限流规则在匹配动作之前都会清空路径变量，所以条件永远看不到其他语句捕获的变量，自己的模式没有声明的变量读到的是 `""`。
在其他任何地方读取路径变量都是策略错误，构建策略、应用权限或规则时会以 `PathVariableScopeException` 拒绝：

| 读取位置 | 拒绝原因 |
|---|---|
| 策略或应用权限的 `condition` | 它在任何动作捕获变量之前运行 |
| 动作不是 `path` 的语句或权限的条件：`all`、`"*"`、含有非 `path` 成员的组合动作、自定义动作类型 | 这些动作不捕获变量 |
| 动作不是 `path` 的限流规则的 `condition` 或 `key` | 同上 |
| 任何动作自身的配置，例如提到 `PATH_VARIABLES` 的模板 | 动作在捕获变量之前求值 |

只要配置中任意深度的某个字符串以 `request.path.var.` 开头，或包含 `PATH_VARIABLES`（保存路径变量的上下文属性，表达式可能访问它），就算读取路径变量。
在运行时拼出这个名字的表达式识别不了，在代码里读取变量的自定义匹配器也识别不了；但因为变量会被清空，它们同样读不到自己语句以外的变量。

组合动作只有在所有成员都是 `path` 动作时才捕获变量，所以下面这条语句会被拒绝：

```json
{
  "action": ["/orders/{orderId}", { "all": { "method": "OPTIONS" } }],
  "condition": { "eq": { "part": "request.path.var.orderId", "value": "1" } }
}
```

## 模板 {#templates}

`path` 模式或 `eq` 的 `value` 中含有 `#{...}` 时，它是一个 SpEL 模板，针对调用方的安全上下文求值，让同一条规则随调用方而变：

| 表达式 | 值 |
|---|---|
| `#{principal.id}` | 主体 id |
| `#{principal.attributes['team']}` | 主体的某个属性 |
| `#{tenant.tenantId}` | 租户 id |

```json
{ "name": "own-profile", "action": "/users/#{principal.id}/**" }
```

模板使用 SpEL 的只读数据绑定：只能读属性和 Map 条目，不能调用方法，也不能写入或构造任何东西。在其他位置，`#{...}` 只是普通文本。
`path` 模板读不到路径变量（[路径变量](#path-variables)）。

### `path` 动作中的模板值 {#template-values-in-path-actions}

在 `path` 动作中，每个 `#{...}` 单独求值，它的值只填充模式中的一段字面量；模式的其余部分保持原样。值为空（或不存在）、为 `.` 或 `..`，
或含有 `*`、`?`、`{`、`}`、`\` 或路径分隔符（`/`，或 `separator` 选项），或在 `decodeAndParseSegments: false` 时含有 `%` 或 `;` 时会被拒绝，否则它会被当作模式语法，或伸进其他路径段。放在 `{...}` 捕获内部的
`#{...}`（例如 `{name:#{...}}`）会在加载策略时失败。被拒绝的值永远不会授予访问：

| 动作所在位置 | 被拒绝的值 |
|---|---|
| `allow` 语句或权限 | 不匹配：请求以 `Implicit Deny` 结束，不是错误 |
| `deny` 语句或权限 | 无论条件如何，请求都以错误失败关闭（`RejectedTemplateValueException`，`500`） |
| 限流规则 | 请求同样以错误失败关闭，且不消耗任何许可 |
| 用 `PathActionMatcherFactory` 在代码中构建、不在语句中使用的匹配器 | 请求同样以错误失败关闭 |

因此，id 或属性含有这些字符的主体无法使用模板化语句：允许永远不会匹配它们，而到达模板化拒绝或限流规则的每个请求都会失败。
`caseSensitive: false` 时，值和其他字面量一样，也会匹配大小写不同的写法：`Alice` 会匹配 `/users/alice/...`。

要服务这样的主体，请用捕获该路径段的静态模式，再用 `eq` 比较；`eq` 按字符串相等比较：

```json
{
  "name": "own-profile",
  "action": "/users/{userId}/**",
  "condition": { "eq": { "part": "request.path.var.userId", "value": "#{principal.id}" } }
}
```

## 条件 {#conditions}

条件是只有一个键的对象，键是条件类型，值是它的配置：

```json
{ "eq": { "part": "context.tenantId", "value": "(platform)" } }
```

每种条件都接受 `"negate": true`，把结果取反。未知类型在构建策略时失败。条件类型：

| 分组 | 类型 |
|---|---|
| [上下文](#context-conditions) | `all`、`authenticated`、`inTenant`、`inRole` |
| [取值](#value-conditions) | `eq`、`in`、`contains`、`startsWith`、`endsWith`、`regular`、`path` |
| [时间](#time-conditions) | `time` |
| [组合](#composing-conditions) | `bool` |
| [表达式](#expressions) | `spel`、`ognl` |

### 上下文条件 {#context-conditions}

| 类型 | 配置 | 何时匹配 |
|---|---|---|
| `all` | `{}` | 总是 |
| `authenticated` | `{}` | 主体不是匿名的 |
| `inTenant` | `value`：`default`、`user` 或 `platform`（不区分大小写） | 租户是默认租户 `(0)`、用户租户（其他任何 id）或平台租户 `(platform)` |
| `inRole` | `value`：角色，写作 `role` 或 `role@space` | 主体拥有该角色（[角色匹配](#matching-roles)） |

`inTenant` 检查的是租户的种类。要检查某个具体的租户 id，用 `eq` 读取 `context.tenantId`。

### 角色匹配 {#matching-roles}

主体的角色是字符串：`editor` 是默认空间中的角色，`editor@team-a` 是空间 `team-a` 中的角色。`inRole` 有两种匹配方式：

| `value` | 匹配持有以下角色的主体 |
|---|---|
| `role@space`，例如 `admin@team-a` | 恰好是 `admin@team-a`，与请求指定的空间无关 |
| 不带后缀的 `role`，例如 `suspended` | `suspended`（在任何空间），或 `suspended@<space>`，其中 `<space>` 是请求的 `CoSec-Space-Id` |

不带后缀的写法让拒绝跟着角色进入各个空间：`inRole: suspended` 的拒绝，在请求指定 `team-a` 时也匹配携带 `suspended@team-a` 的令牌；
而不带后缀持有的角色在所有空间都匹配，所以换一个空间也逃不出这样的拒绝。

两种写法都不会把**允许**限定在请求的空间内。`admin@team-a` 的允许在请求指定 `team-b` 或不指定空间时同样匹配。要只在某个空间内授权，加一个 `request.spaceId` 条件：

```json
{
  "name": "team-a-admins",
  "action": "/team-a/**",
  "condition": {
    "bool": {
      "and": [
        { "inRole": { "value": "admin@team-a" } },
        { "eq": { "part": "request.spaceId", "value": "team-a" } }
      ]
    }
  }
}
```

应用权限中的角色授权不需要这样的条件，它们本身就限定在请求的空间内（[角色授权范围](./role-permissions.md#role-grant-scope)）。
基于主体角色的表达式看到的是原始字符串，包括 `suspended@team-a`。

### 取值条件 {#value-conditions}

这些条件读取请求或安全上下文的一个 [part](#parts)，并按字符串比较。

| 类型 | 配置 | part 满足以下条件时匹配 |
|---|---|---|
| `eq` | `part`、`value`（可以用[模板](#templates)）、`ignoreCase` | 等于 `value` |
| `in` | `part`、`value`（字符串数组） | 等于 `value` 中的某一个（区分大小写） |
| `contains` | `part`、`value`、`ignoreCase` | 包含 `value` |
| `startsWith` | `part`、`value`、`ignoreCase` | 以 `value` 开头 |
| `endsWith` | `part`、`value`、`ignoreCase` | 以 `value` 结尾 |
| `regular` | `part`、`pattern`、`timeout`（毫秒，默认 `1000`） | 整体匹配正则表达式，不区分大小写 |
| `path` | `part`、`pattern`、`options` | 匹配路径模式；`options` 同 [`path` 动作](#path) |

`ignoreCase` 默认为 `false`。`regular` 在 `timeout` 时限内运行，防止灾难性回溯；超时的匹配永远不会被当作匹配，并拒绝请求：匿名调用方得到 `401`，已认证调用方得到 `403`
（[响应](../integration/web.md#responses-and-status-codes)）。

```json
{ "in": { "part": "request.remoteIp", "value": ["10.0.0.1", "10.0.0.2"] } }
```

```json
{ "regular": { "part": "request.header.User-Agent", "pattern": ".*curl.*" } }
```

```json
{ "path": { "part": "request.attributes.target", "pattern": "order.*", "options": { "separator": "." } } }
```

### Part {#parts}

完整列表，与 `DefaultPartExtractor` 的读取方式一致。不存在的 part 读到 `""`。

| Part | 值 |
|---|---|
| `request.path` | 请求路径 |
| `request.method` | HTTP 方法 |
| `request.remoteIp` | 客户端 IP（[代理之后](../integration/web.md#client-ip-behind-proxies)） |
| `request.appId`、`request.spaceId`、`request.deviceId` | 请求头或查询参数 `CoSec-App-Id`、`CoSec-Space-Id`、`CoSec-Device-Id`（[应用与空间](../integration/web.md#app-and-space)） |
| `request.origin`、`request.origin.host` | `Origin` 请求头及其主机 |
| `request.referer`、`request.referer.host` | `Referer` 请求头及其主机 |
| `request.header.<name>` | 任意请求头 |
| `request.attributes.<name>` | [请求属性](../integration/web.md#request-attributes)，例如 `ipRegion` |
| `request.path.var.<name>` | 同一条语句的 `path` 动作捕获的变量（[路径变量](#path-variables)） |
| `resource.<name>` | 目标对象的属性，由[代码中的检查](../integration/checks.md#object-level-rules)设置；在请求过滤器中为 `""` |
| `context.tenantId` | 租户 id |
| `context.principal.id` | 主体 id |
| `context.principal.attributes.<name>` | 主体属性，转为字符串 |

带前缀的 part 在前缀后面必须有名字。取值条件的 part 不在这个列表中时，策略在加载时就会失败，报 `Condition [<type>] part [<part>] is not a supported part`。
7.0 之前的版本存储的副本，在修正并重新保存之前，每次读取该策略都会以 `500` 失败。

请求头、`request.remoteIp` 和请求属性的可信度取决于它们的来源。除非可信代理会覆盖某个请求头，否则不要基于客户端可以设置的请求头做允许决定。

### 时间条件 {#time-conditions}

`time` 在有效期内匹配，还可以加上每天重复的时间窗口。它读取服务器时钟，从不读取请求。

| 配置 | 含义 |
|---|---|
| `notBefore`、`notAfter` | 有效期，ISO 日期时间，`notBefore` 包含、`notAfter` 不包含。不带时区偏移的日期时间按 `zone` 解读。 |
| `daysOfWeek` | 条件生效的星期，例如 `["MON", "TUE"]`；也可以写全称。 |
| `from`、`to` | 每天的窗口 `[from, to)`，格式 `HH:mm` 或 `HH:mm:ss`。`from` 晚于 `to` 时窗口跨过午夜，凌晨那部分属于窗口开始的那一天，`daysOfWeek` 检查的也是那一天。 |
| `zone` | 窗口和不带偏移的日期时间所用的时区。默认 `UTC`。 |

构建策略时会检查配置：`notBefore`、`notAfter`、`daysOfWeek`、`from`/`to` 至少有一个；`from` 和 `to` 必须同时出现且不相等；
`notBefore` 早于 `notAfter`；`daysOfWeek` 不为空。

```json
{
  "name": "temporary-support-access",
  "action": "/orders/**",
  "condition": {
    "bool": {
      "and": [
        { "inRole": { "value": "support" } },
        { "time": { "notAfter": "2026-12-31T00:00:00", "zone": "Asia/Shanghai" } }
      ]
    }
  }
}
```

```json
{
  "name": "no-writes-outside-business-hours",
  "effect": "deny",
  "action": { "all": { "method": ["POST", "PUT", "PATCH", "DELETE"] } },
  "condition": {
    "time": { "daysOfWeek": ["MON", "TUE", "WED", "THU", "FRI"], "from": "09:00", "to": "18:00", "zone": "Europe/Berlin", "negate": true }
  }
}
```

## 组合条件 {#composing-conditions}

`bool` 组合多个条件：

| 键 | 含义 |
|---|---|
| `and` | 每个条件都必须匹配 |
| `or` | 存在且不为空时，还必须至少匹配一个 |
| `negate` | 把组合后的结果取反 |

`{ "bool": {} }` 总是匹配。`bool` 可以任意嵌套。

```json
{
  "bool": {
    "and": [{ "authenticated": {} }],
    "or": [
      { "inRole": { "value": "admin" } },
      { "eq": { "part": "context.principal.attributes.team", "value": "ops" } }
    ]
  }
}
```

## 表达式 {#expressions}

| 类型 | 配置 | 求值方式 |
|---|---|---|
| `spel` | `expression` | 只读数据绑定的 Spring Expression Language。根对象有 `request` 和 `context`：`context.principal.attributes['tier'] == 'gold'`。只能读属性和 Map 条目，调用方法会失败。 |
| `ognl` | `expression` | 沙箱中的 OGNL。根对象是请求（`path`、`method`、`appId` 等）；`#request` 和 `#context` 分别是请求和安全上下文：`#context.principal.roles.contains('admin')`。 |

```json
{ "spel": { "expression": "context.principal.attributes['tier'] == 'gold'" } }
```

```json
{ "ognl": { "expression": "#context.principal.roles.contains('admin') && method == 'GET'" } }
```

优先使用声明式条件。表达式更难评审和测试，两者的风险也不同：

- **SpEL** 只能读属性和 Map 条目：不能调用方法、不能写入、不能引用类型或构造对象。策略作者不完全可信时选它。
- **OGNL** 可以调用 public 方法，所以表达力更强，风险也更大。沙箱禁止非 public 成员和字段、setter 以及会修改集合的方法（`put`、`add`、`remove`、`clear` 等）、
  `getClass` 和 `getDelegate`、`Runtime`、`Process`、`System`、`Thread`、`Class`、`ClassLoader` 以及 `java.io`、`java.nio`、`java.lang.reflect` 等包中的成员，
  并拒绝一切类引用（`new`、`@Class@member`）。只读检查依据方法名和签名，属于尽力而为；请把 OGNL 策略当作代码对待。
- 两者都不检查 [part](#parts)，对[路径变量](#path-variables)也只做 `PATH_VARIABLES` 文本检查；表达式出错时请求以 `500` 失败。

## 加载策略文件 {#loading-policy-files}

策略通过 `PolicyRepository` 进入授权。本地文件是填充它的一种方式，如何开启以及如何初始化仓库见[存储](../operations/storage.md#local-policy-files)。
凡是从文件读取策略的地方（本地策略、[影子候选](./testing.md#trying-changes-in-shadow-mode)、[`PolicyTestKit`](./testing.md#policy-test-cases)、
[`AccessAnalyzer`](./testing.md#access-reviews)），加载都是严格的，因为被悄悄跳过的文件可能丢掉一条拒绝：

- 无法读取或构建的文件会失败：无效 JSON、缺少必填字段、未知的动作或条件类型、无效的匹配器配置（例如没有任何边界的 `time` 条件，
  或使用了不支持的 [part](#parts) 的取值条件），或在作用域之外读取[路径变量](#path-variables)。错误信息会给出文件名。
- 两个文件声明同一个策略 id 会失败，并给出两个文件名。
- 配置的位置不存在或匹配不到任何文件会失败。只有默认位置 `classpath:cosec-policy/*-policy.json` 允许什么都匹配不到。
- 被两个位置同时匹配的文件只读一次。

在应用中，这些失败会让启动失败。取决于请求的错误在这里发现不了，需要[校验策略](./testing.md#validating-policies)。

## 从 OpenAPI 生成策略 {#generating-policies-from-openapi}

有 `cosec-openapi` 和 Spring Boot Actuator 时，`GET /actuator/cosecPolicyGenerator` 把应用的 OpenAPI 描述转成策略草稿：
每个操作一条 `allow` 语句，动作是包含该操作路径和方法的 `path` 动作，名称取操作的 summary（或 operation id）。
草稿的 id 是占位值 `PolicyId`，类型为 `system`，租户为 `(0)`。使用前请设置 id 和元数据，收窄或删除语句，并经过评审。
应用权限目录也可以用同样的方式生成（[从 OpenAPI 生成权限目录](./role-permissions.md#generating-a-catalog-from-openapi)）。

## JSON Schema {#json-schema}

[`schema/`](https://github.com/Ahoo-Wang/CoSec/tree/main/schema) 中的 JSON Schema 可用于编辑器补全和提交前检查：

| 文件 | 校验对象 |
|---|---|
| `cosec-policy.schema.json` | 策略文件 |
| `cosec-app-permission.schema.json` | 应用权限文件 |
| `rate-limit-rule.schema.json` | 限流规则文件 |
| `action.schema.json`、`condition.schema.json`、`definitions.schema.json` | 共用部分 |

在文件中用 `$schema` 引用它，解析器会忽略这个字段：

```json
{
  "$schema": "https://raw.githubusercontent.com/Ahoo-Wang/CoSec/main/schema/cosec-policy.schema.json",
  "id": "orders",
  "name": "Orders",
  "type": "custom",
  "tenantId": "(platform)",
  "statements": [{ "action": "/orders/**" }]
}
```

Schema 只描述结构，不检查 part、路径变量作用域、重复 id 或匹配器语义，所以还要用真正的解析器加载策略（[测试](./testing.md#validating-policies)）。

**下一步：** 按角色而不是按主体授予权限，见[角色权限](./role-permissions.md)。

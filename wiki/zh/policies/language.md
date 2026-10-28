---
title: "策略语言"
description: "CoSec 文档每个字段的含义：平台、应用和租户文档，策略、语句、动作、资源、条件、绑定、目录和限流规则。"
---

# 策略语言 {#policy-language}

面向需要字段确切含义的策略作者、评审者和工具开发者。本页是文档格式（`schema: 2`）的参考。这些字段如何组合成决策，见[安全模型](../guide/security-model.md)；如何安全地修改文档，见[测试策略](./testing.md)。

所有授权数据都保存在三种文档中。文档按严格模式读取、整体编译：未知字段、重复的键、JSON 类型不对的值、无法编译的模式或条件、悬空的引用，都会让整个文档失败。失败的文档永远不会被安装，所有需要它的请求都失败即拒绝：除非有 DENY 匹配，否则返回 `503`（[失败即拒绝](../guide/security-model.md)）。

## 文档 {#documents}

| 文档 | 键 | 每个……一份 | 包含 |
|---|---|---|---|
| 平台文档 | `platform` | 部署 | 所有租户共享的策略、面向任何人（`*`）和指定主体的绑定、break-glass 绑定、限流规则，以及只有平台能设置的 `limits`、`attributes`、`boundaries`。 |
| 应用文档 | `app:<appId>` | 应用 | 应用的[目录](#catalogs)、权限策略 `perm:<appId>/<permission>`、角色绑定、限流规则。 |
| 租户文档 | `tenant:<tenantId>` | 租户 | 租户自己的策略、它的绑定（任何人、它的主体、它的角色）、它的 `boundary`、限流规则。 |

键是[控制面](./testing.md)对文档的称呼。每个请求都读取平台文档和它所属租户的文档（匿名请求：租户 `(0)`）。只有当主体在请求的空间中至少拥有一个角色时，请求才会读取其应用的应用文档；被黑名单拦截的请求只读取平台文档。没有发布过的文档按空文档读取。哪些候选适用、按什么顺序，见[安全模型](../guide/security-model.md)。

一个租户文档：

```json
{
  "schema": 2,
  "revision": 3,
  "publishedAt": "2026-11-01T08:00:00Z",
  "policies": [
    {
      "id": "order-reader",
      "statements": [
        {
          "name": "read-orders",
          "effect": "allow",
          "action": "order:read",
          "resource": "order/**"
        }
      ]
    }
  ],
  "bindings": [
    {
      "id": "support-reads-orders",
      "subject": { "role": "support" },
      "app": "order",
      "space": "",
      "policy": "order-reader"
    }
  ]
}
```

### 文档头 {#header}

| 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| `schema` | 整数 | 是 | 格式版本，固定为 `2`。其他值都会让文档失败。 |
| `revision` | 整数 | 是 | 正数。每次发布写入下一个修订。 |
| `publishedAt` | 字符串 | 是 | 带 `Z` 或偏移量的 ISO-8601 时刻，例如 `2026-11-01T08:00:00Z`。 |
| `provenance` | 对象 | 否 | 该修订是如何发布的（[来源记录](#provenance)）。由控制面和 `cosec-migrate` 写入。 |

通过控制面提交整份文档（`:apply`）时，文档头必须存在且合法，但控制面发布时会写入它自己的 `revision`、`publishedAt` 和 `provenance`。

### 各文档的字段 {#sections-by-document}

| 字段 | 平台 | 应用 | 租户 |
|---|---|---|---|
| `policies`、`bindings`、`rateLimits` | 是 | 是 | 是 |
| `catalog` | | 是 | |
| `limits`、`attributes` | 是 | | |
| `boundaries` | 是 | | |
| `boundary` | | | 是 |

文档出现其种类不接受的字段时，该文档失败。

## 策略 {#policies}

策略是一组有名字的语句。只有[绑定](#bindings)或[附加策略](#attachments)让它适用于某人时，它才起作用。

| 字段 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `id` | 字符串 | 必填 | 在所属文档内唯一。规则见下。 |
| `attachable` | 布尔 | `false` | 主体能否[附加](#attachments)它。 |
| `boundary` | 布尔 | `false` | 平台[边界](#boundaries)策略。 |
| `condition` | 条件 | 无 | 守护策略的每条语句。它不能读取[路径变量](#path-variables)。每个请求最多求值一次，并且只在某条语句的动作和资源已匹配之后求值。 |
| `statements` | 数组 | 必填 | [语句](#statements)。 |

策略 id：

- `perm:<appId>/<permission>` 是应用的权限策略。只有该应用的文档能定义它，且它不能被附加（[角色权限](./role-permissions.md)）。
- `cosec:blacklist`、`cosec:root`、`cosec:platform-admin`、`cosec:tenant-admin` 和 `cosec:tenant-viewer` 是内置策略，任何文档都不能定义它们。控制面还会拒绝以 `cosec:`、`g:` 或 `r:` 开头的新 id，这些前缀保留给内置策略和 `cosec-migrate`。
- 租户策略不能复用平台策略的 id。控制面会拒绝；在数据面，绑定或附加策略指向这样的 id 时，除非有 DENY 匹配或两个策略内容相同，请求为 Indeterminate（`503`）。

## 语句 {#statements}

当语句的某个动作模式匹配请求的动作、某个资源模式匹配请求的资源、其条件为真且其所属策略的条件为真时，语句匹配请求。

| 字段 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `name` | 字符串 | `""` | 出现在决策、追踪和 `explain` 中。 |
| `effect` | `"allow"` 或 `"deny"` | 必填 | |
| `action` | [动作模式](#actions)，或其非空数组 | 必填 | 所有动作写 `"*"`。 |
| `resource` | [资源模式](#resources)或模式对象，或其非空数组 | 任意资源 | |
| `condition` | [条件](#conditions) | 恒为真 | |
| `onMissing` | `"indeterminate"` 或 `"nomatch"` | `"indeterminate"` | 无法获取的外部属性意味着什么（[外部属性](#external-attributes-and-onmissing)）。只用于读取外部属性的条件。 |
| `onMissingConfirmed` | 对象 | 无 | 谁在哪个变更中确认了一条 `onMissing: "nomatch"` 的 DENY：`{"by": {"tenant", "principal"}, "change"}`。只有控制面写入它。 |

DENY 不能写裸资源类型，例如 `order`：它只覆盖 id 为空的那个资源。要覆盖所有订单，写 `order/**`。这样的语句无法编译。

## 动作 {#actions}

| 模式 | 匹配 |
|---|---|
| `service:verb` | 仅该动作。 |
| `service:*` | 该服务的所有动词。 |
| `*` | 所有动作。 |

没有其他通配符。服务和动词精确比较，区分大小写（`http:get` 不是 `http:GET`）。两者都不能为空，也不能包含 `:`、`*`、空白或控制字符。

请求的动作从哪里来：

- 路由在服务[路由映射](#route-maps)中的 HTTP 请求，动作和类型化资源取自该路由的 `service:verb`。
- 其他 HTTP 请求的动作是 `http:<METHOD>`（按收到的方法，例如 `http:GET`），资源类型为 `http`、id 为请求路径。针对 `service:verb` 的语句永远不会匹配它，针对 `http:*` 的语句也永远不会匹配已映射的请求。
- 代码中的检查自己指定动作和资源（[代码中的检查](../integration/checks.md)）。

### 路由映射 {#route-maps}

路由映射（`cosec-routes.json`，从 `cosec.authorization.routes.location` 加载，见[配置](../operations/configuration.md)）为服务的 HTTP 路由指定动作和类型化资源：

```json
{
  "service": "order",
  "routes": [
    { "method": "GET", "path": "/orders/{orderId}", "action": "order:read", "resource": "order/{orderId}" },
    { "method": "POST", "path": "/orders/{orderId}/cancel", "action": "order:cancel", "resource": "order/{orderId}" }
  ]
}
```

`path` 是 [HTTP 路径模式](#http-paths)。`resource` 是类型化资源，其片段是字面量或 `path` 中的变量。路由映射要么整体加载，要么不加载：有格式错误的路由，或同一方法下两条可能匹配同一请求的路由，都会让启动失败。包含 `/`（解码后的 `%2F`）的捕获值会改变资源 id 的片段，因此这样的请求会以 `400` 被拒绝。网关不加载路由映射：它的请求留在 `http` 命名空间。`cosecServiceGenerator/routes` 可以从服务的 OpenAPI 文档生成路由映射（[角色权限](./role-permissions.md#generating-a-catalog-from-openapi)）。

## 资源 {#resources}

资源由类型和 id 组成：`order/42` 是 `order` 和 `42`，HTTP 路径是 `http` 和 `/orders/42`。

### 类型化资源 {#typed-resources}

`type`，或 `type/segment/...`。每个片段是以下之一：

| 片段 | 匹配 |
|---|---|
| 字面量 | 完全相同的文本，区分大小写 |
| `*` | 一个非空片段 |
| `{name}` | 一个非空片段，捕获为[路径变量](#path-variables) |
| `**`，只能在最后 | 任意数量的后续片段，包括零个 |

| 模式 | `order`（id 为空） | `order/42` | `order/42/items` |
|---|---|---|---|
| `order` | 是 | | |
| `order/*` | | 是 | |
| `order/{orderId}` | | 是 | |
| `order/**` | 是 | 是 | 是 |
| `order/*/items` | | | 是 |

id 按 `/` 切分并按原样比较：从不解码，从不去除空白。类型不能是 `http`，也不能包含 `:`；字面量不能包含 `*`、`?`、`{`、`}`、空白或控制字符。

### HTTP 路径 {#http-paths}

`http:path:<pattern>` 匹配类型为 `http`、id 为请求路径（经管道规范化，见 [Web 集成](../integration/web.md)）的资源。在以下子集上，它与 Spring 的 `PathPattern` 匹配结果一致：

| 元素 | 匹配 |
|---|---|
| 字面量片段 | 该片段 |
| `?` | 一个字符 |
| 片段内的 `*` | 零个或多个字符，例如 `/files/*.pdf` |
| 作为片段的 `*` | 一个片段 |
| `{name}` | 一个片段，并捕获 |
| `**` 或 `{*name}` | 任意数量的片段，`{*name}` 把路径余下部分捕获下来；只能在开头或结尾，每个模式至多一个。`/admin/**` 也匹配 `/admin`。 |

以下写法无法编译：`{name:regex}`、空模式、除单独的 `*` 之外可能匹配空值的片段（例如 `{a}{b}` 或 `*{a}`），以及 `{}` 和 `{*}`。

资源可以写成带选项的模式对象，仅限 `http:path:` 模式：

```json
{ "pattern": "http:path:/Reports/{id}", "caseSensitive": false, "decodeAndParseSegments": true }
```

| 选项 | 默认值 | 含义 |
|---|---|---|
| `caseSensitive` | `true` | 为 `false` 时比较字面量忽略 ASCII 大小写。 |
| `decodeAndParseSegments` | `true` | 为 `true` 时按百分号解码、去掉 `;` 参数后的值匹配每个片段；为 `false` 时按收到的原文匹配，但 `%2F` 仍读作 `/`。 |

## 路径变量 {#path-variables}

类型化或 HTTP 模式中的 `{name}`，以及 HTTP 模式中的 `{*name}`，会捕获一个变量。只有捕获它的那条语句能读取它，形式为 `resource.var.<name>`，用在该语句自己的条件或限流规则的 `key` 中：

```json
{
  "name": "own-profile",
  "effect": "allow",
  "action": "http:*",
  "resource": "http:path:/users/{userId}/**",
  "condition": { "eq": ["resource.var.userId", { "attr": "subject.id" }] }
}
```

- 语句的每个资源模式都必须捕获它读取的变量，否则语句无法编译：某个模式没有捕获的变量会读作缺失，针对它写的 DENY 会悄悄失效。策略条件不能读取任何变量。
- 变量名是 Java 标识符，另外允许包含 `-`；同一模式中一个名字只能捕获一次。
- 捕获的值是该片段解码后的值（`{*name}` 为路径余下部分）。值只会被比较，永远不会成为模式的一部分，所以 id 为 `**` 的主体无法扩大任何范围。

## 条件 {#conditions}

条件是只含一个运算符的 JSON 对象；`ignoreCase` 可以与 `eq` 和 `prefix` 并列。每个属性路径和每个操作数类型都在文档编译时检查。

```json
{ "and": [
  { "eq": ["subject.authenticated", true] },
  { "in": ["scope.space", ["team-a", "team-b"]] },
  { "not": { "cidr": ["environment.ip", ["10.0.0.0/8"]] } },
  { "eq": ["resource.var.ownerId", { "attr": "subject.id" }] }
] }
```

### 运算符 {#operators}

| 运算符 | 形式 | 为真当 | 属性类型 |
|---|---|---|---|
| `eq` | `[attribute, literal]` | 值等于该字面量。加 `"ignoreCase": true`（仅 String）时忽略 ASCII 大小写。Ip 属性与地址比较。 | String、Bool、Ip |
| `eq` | `[attribute, {"attr": attribute}]` | 两个值都存在且相等；两个属性类型相同 | String、Bool、Ip |
| `in` | `[attribute, [literals]]` | 值是其中某个字面量：1 到 `maxInValues` 个 | String、Bool、Ip |
| `in` | `[literal, attribute]` | 集合包含该字符串字面量 | Set |
| `prefix` | `[attribute, "text"]` | 值以该文本开头；可加 `ignoreCase`。对 Labels 属性按完整标签匹配：`com.example` 匹配 `example.com` 和 `api.example.com`，不匹配 `badexample.com`。 | String、Labels |
| `cidr` | `[attribute, ["block", ...]]` | 地址落在某个 IPv4 或 IPv6 网段中：1 到 `maxCidrBlocks` 个 | Ip |
| `time` | `{...}` | 请求时间在时间窗口内（[时间条件](#time-conditions)） | |
| `exists` | `"attribute"` | 属性存在（Labels 属性看其主机） | 任意 |
| `and` | `[conditions]` | 全部为真；至少一个，从左到右，遇到第一个假即停止 | |
| `or` | `[conditions]` | 任一为真；至少一个，从左到右，遇到第一个真即停止 | |
| `not` | `condition` | 条件为假 | |

字面量是 JSON 字符串或布尔值，从不是数字；字符串永远不会被当作布尔值。没有 `true` 或 `false` 条件：不需要条件时省略 `condition`。

### 属性 {#attributes}

| 属性 | 类型 | 值 |
|---|---|---|
| `subject.id` | String | 主体 id；匿名主体时缺失。 |
| `subject.tenant` | String | 请求的租户：主体的租户，匿名时为 `(0)`。 |
| `subject.authenticated` | Bool | 凭证是否被接受。 |
| `subject.roles` | Set | 主体在任意空间中的角色 id。 |
| `subject.scopedRoles` | Set | 按声明形式给出的角色：默认空间为 `role`，其他空间为 `role@space`。 |
| `subject.attributes.<name>` | String | 令牌 `attributes` 声明中的条目，转为文本（[认证](../integration/authentication.md)）。 |
| `scope.tenant` | String | 同 `subject.tenant`。 |
| `scope.app` | String | 请求的应用；没有应用时缺失。 |
| `scope.space` | String | 请求的空间；默认空间为 `""`。 |
| `scope.roles` | Set | 主体在请求空间中的角色。 |
| `action.service`、`action.verb` | String | 动作的两部分。 |
| `resource.type`、`resource.id` | String | 资源的两部分。 |
| `resource.attributes.<name>` | String | 代码在检查时传入的资源属性。请求管道不设置任何值。 |
| `resource.var.<name>` | String | [路径变量](#path-variables)。 |
| `environment.ip` | Ip | 客户端地址（[Web 集成](../integration/web.md)）。 |
| `environment.time` | Instant | 请求时间；只有 `time` 读取它。 |
| `environment.device` | String | `CoSec-Device-Id` 参数；为空时缺失。 |
| `environment.blocked` | Bool | 黑名单是否拦截该请求。 |
| `http.header.<name>` | String | 请求头 `<name>`；请求没有该头时缺失。见[请求头](#headers)。 |
| `http.origin`、`http.referer` | String | `Origin` 和 `Referer` 请求头，读取方式与 `http.header.*` 相同。 |
| `http.origin.host`、`http.referer.host` | String | `Origin` 和 `Referer` URL 中的主机，小写；值不是带主机的 URL 时缺失。 |
| `http.origin.host.labels`、`http.referer.host.labels` | Labels | 该主机的标签，倒序（`api.example.com` 为 `com.example.api`），小写；供 `prefix` 使用。 |
| `request.attributes.<name>` | String | 宿主掌握的 HTTP 之外的请求信息。网关设置 `request.attributes.cosec.audit.routeId`，即请求经过的路由 id；对元数据声明不传播身份（`cosec-identity: false`）的路由，还设置 `request.attributes.cosec.identity.forward`（`false`）。WebFlux 和 WebMvc 不设置任何值。 |
| `ext.<provider>.<name>` | 声明的类型 | [外部属性](#external-attributes-and-onmissing)。 |

不在此表中的路径无法编译。

### 请求头 {#headers}

请求头名称是 ASCII token（RFC 9110：字母、数字和 ``!#$%&'*+-.^_`|~``），其他名称无法编译。名称不区分大小写，只折叠 ASCII 字母，文档一侧和请求一侧都是如此：`http.header.X-Team` 就是 `http.header.x-team`，任何 Unicode 大小写映射都不会让两个不同的名称相同。

任何属性引用都不能读取携带凭证的请求头：`http.header.authorization`、`proxy-authorization`、`cookie`、`set-cookie`、
`x-api-key` 和 `cosec-identity`，大小写不限，条件和限流规则的 `key` 都是如此。含有这种引用的文档无法编译，无论经由控制面还是
`cosec-migrate`：在 `authorization` 上用 `prefix` 写的 DENY 会让文档作者逐个字符地获知其他主体的令牌，以它为 `key` 则会按令牌计数。`cosec-identity` 是网关转发给后端服务的请求头，在网关上它只是客户端的声明。这份列表是固定的
（[`ConditionChecker.CREDENTIAL_HEADERS`](https://github.com/Ahoo-Wang/CoSec/blob/main/model/src/main/kotlin/me/ahoo/cosec/model/condition/ConditionChecker.kt)）；
从其他请求头读取 API key 的宿主不在其覆盖范围内。

同一请求头出现多次且每份值都相同时，读作该值。各份值不同时，该请求头没有单一的值，读取它的语句出错：基于它的 DENY
让请求成为 Indeterminate（`503`），除非另有 DENY 匹配；基于它的 ALLOW 不匹配。第一份或最后一份值永远不会单独决定结果。

### 匹配角色 {#matching-roles}

角色通过[角色绑定](./role-permissions.md)授予权限，而不是通过条件。确实需要在条件中判断角色时，检查集合：

```json
{ "in": ["auditor", "scope.roles"] }
```

`scope.roles` 只包含请求空间中的角色，通常这就是你要的；`subject.roles` 包含角色而不论它在哪个空间；`subject.scopedRoles` 包含 `role@space` 形式的字符串。匿名主体没有角色。

### 缺失值 {#absent-values}

请求没有某属性的值时，该属性缺失，例如匿名主体的 `subject.id`，或没有应用的请求的 `scope.app`。

- 对缺失值，`eq`、`in`、`prefix`、`cidr` 和 `time` 为假；`exists` 为假。
- 缺失值不等于任何值：不等于 `""`，也不等于另一个缺失值。
- `not` 是普通的取反，所以对缺失值比较取 `not` 为真。写成 `{"not": {"eq": [...]}}` 的 DENY 也会匹配属性缺失的请求。

### 时间条件 {#time-conditions}

`time` 读取 `environment.time`。

| 字段 | 含义 |
|---|---|
| `notBefore` | ISO 日期时间；在它之前条件为假。不带偏移量时按 `zone` 解读。 |
| `notAfter` | ISO 日期时间；从它开始条件为假。必须晚于 `notBefore`。 |
| `zone` | 时区 id：`UTC`（默认）、偏移量如 `+08:00` 或 `UTC+8`，或地区如 `Asia/Shanghai`。 |
| `daysOfWeek` | 非空数组，元素为 `MON` … `SUN`。 |
| `from`、`to` | 一天中的时间，`HH:mm`、`HH:mm:ss` 或 `HH:mm:ss.fff`；要么都写要么都不写，且不能相等。窗口为 `[from, to)`。`from` 晚于 `to` 时窗口跨越午夜，凌晨部分属于窗口开始的那一天。 |

`notBefore`、`notAfter`、`daysOfWeek` 或 `from`/`to` 至少写一个。

```json
{ "time": { "zone": "Asia/Shanghai", "daysOfWeek": ["MON", "TUE", "WED", "THU", "FRI"], "from": "09:00", "to": "18:00" } }
```

### 外部属性与 onMissing {#external-attributes-and-onmissing}

`ext.<provider>.<name>` 属性来自属性提供者 `AttributeProvider`（[扩展](../architecture/extending.md)），只在请求的候选语句读取它们时获取。它们的类型是声明的：`string`、`bool`、`stringSet` 或 `ip`。没有任何提供者、也没有平台 [`attributes`](#limits-and-attributes) 条目声明的路径无法编译。每个实例都按自己的提供者和平台的 `attributes` 编译，控制面检查变更时也是如此；因此每台主机上的提供者必须相同（[测试策略](./testing.md#trying-changes-in-shadow-mode)）。

提供者可能不返回值（即上文的缺失），也可能失败、超时或没有被调用：此时属性*不可用*，由语句的 `onMissing` 决定：

| `onMissing` | DENY | ALLOW |
|---|---|---|
| `indeterminate`（默认） | 除非另有 DENY 匹配，决策为 Indeterminate（`503`）。 | 跳过。若没有其他语句允许，决策为 Indeterminate（`503`），而不是拒绝。 |
| `nomatch` | DENY 不匹配：属性不可用期间它失效。只有带 `onMissingConfirmed` 才能编译，该字段由控制面在变更作者确认后写入（[测试策略](./testing.md#changes)）。 | 跳过，请求在没有它的情况下决策。 |

不可用的属性永远不会把 Deny 或 Indeterminate 变成 Permit，经确认的 `nomatch` DENY 除外。

### 限制 {#limits}

每个条件在编译时都有上限。平台文档为所有文档设置这些上限（[`limits`](#limits-and-attributes)）；未写的取默认值。

| 限制 | 默认值 | 限定 |
|---|---|---|
| `maxNodes` | 64 | 单个条件的节点数。 |
| `maxDepth` | 8 | 单个条件的嵌套深度。 |
| `maxInValues` | 1024 | 单个 `in` 的字面量数，以及单个 `time` 的天数。 |
| `maxCidrBlocks` | 256 | 单个 `cidr` 的网段数。 |
| `maxLiteralBytes` | 1024 | 单个字面量或属性路径的 UTF-8 字节数。 |
| `maxCost` | 256 | 单个条件的节点权重之和：`and`、`or`、`not`、`exists` 为 1；`eq`、`prefix` 为 2；`in`、`cidr` 为 4；`time` 为 8。 |

## 绑定 {#bindings}

绑定表示：*主体*在*应用*和*空间*中受*策略*约束。

| 字段 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `id` | 字符串 | 必填 | 在所属文档内唯一。控制面拒绝以 `cosec:`、`g:` 或 `r:` 开头的新 id。 |
| `subject` | `"*"` 或对象 | 必填 | 对谁生效，见下。 |
| `app` | 字符串 | 必填 | 应用 id，或 `*` 表示任意应用。不能为空。 |
| `space` | 字符串 | 必填 | 空间 id，`""` 表示默认空间，`*` 表示任意空间。 |
| `policy` | 字符串 | 必填 | 策略 id。 |
| `expiresAt` | 字符串 | 无 | ISO-8601 时刻。从该时刻起绑定不再适用。 |
| `breakGlass` | 布尔 | `false` | break-glass 绑定（[安全模型](../guide/security-model.md)）。 |

`app` 和 `space` 精确比较，区分大小写；`*` 表示任意，且只表示任意。

| 主体 | 平台 | 应用 | 租户 |
|---|---|---|---|
| `"*"`：任何人，包括匿名主体 | 是 | | 是 |
| `{"principal": "<id>", "tenant": "<tenant>"}` | 是，`tenant` 必填 | | 是，`tenant` 可省略（本租户） |
| `{"role": "<id>"}`，可选 `"tenant"` | | 是；无 `tenant`：任意租户 | 是；无 `tenant`：本租户 |

租户文档中指向其他租户的绑定、限定到其他应用的应用文档绑定，以及平台文档中的角色绑定都无法编译：它们永远不可能适用，DENY 会悄悄丢失。

`policy` 可以指向：

- 本文档中的策略；
- 在应用或租户文档中，平台策略。它在每个请求时查找：如果平台已不再有该策略，它所适用的请求在没有 DENY 匹配时为 Indeterminate（`503`）。控制面拒绝发布无法解析的引用，也拒绝删除被其他文档引用的平台策略；
- 应用的权限策略：只能在该应用的文档中，或在 `app` 设为该应用的租户文档中（[角色权限](./role-permissions.md#tenant-role-bindings)）；
- `cosec:root`，只能由 break-glass 绑定指向；`cosec:platform-admin`、`cosec:tenant-admin` 和 `cosec:tenant-viewer`，用于授权控制面。

边界策略和 `cosec:blacklist` 永远不能被绑定。break-glass 绑定只在平台文档中，绑定 `(platform)` 租户的主体，并带有 `expiresAt`。一个文档至多绑定 1,000 个主体：要授权用户，请通过令牌中的角色或 `MembershipProvider`。

## 附加策略 {#attachments}

主体的成员关系可以列出直接附加的策略：令牌的 `policies` 声明，或 `MembershipProvider`（[认证](../integration/authentication.md)）。附加策略在所有应用和空间中都适用。该策略必须是 `attachable: true`，并定义在主体所属租户的文档或平台文档中。指向其他任何策略的附加策略视为丢失，除非有 DENY 匹配，请求为 Indeterminate（`503`）。匿名主体没有附加策略。

## 目录 {#catalogs}

应用文档的 `catalog` 列出应用提供的权限，供控制台和生成器使用。它不参与求值。

| 字段 | 类型 | 含义 |
|---|---|---|
| `groups` | 数组，必填 | 权限分组。 |
| `groups[].name` | 字符串，必填 | |
| `groups[].description` | 字符串 | |
| `groups[].spaced` | 布尔，默认 `false` | 该组权限是否打算按空间授予；仅作提示。 |
| `groups[].permissions` | 数组，必填 | |
| `groups[].permissions[].id` | 字符串，必填 | 权限 id；其策略为 `perm:<appId>/<id>`。 |
| `groups[].permissions[].name`、`.description` | 字符串 | |

没有任何检查确保目录条目有对应的策略，反之亦然。见[角色权限](./role-permissions.md)。

## 限流规则 {#rate-limit-rules}

`rateLimits` 是有序的规则数组。被允许的请求在它匹配的每条规则上消耗一个许可（[限流](./rate-limiting.md)）。

| 字段 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `id` | 字符串 | 必填 | 字母、数字、`.`、`_` 和 `-`。在所属文档内唯一。 |
| `action` | 动作模式或其非空数组 | 必填 | 哪些请求计数。 |
| `resource` | 资源模式或其非空数组 | 任意资源 | |
| `condition` | 条件 | 恒为真 | |
| `onMissing` | `"indeterminate"` 或 `"nomatch"` | `"indeterminate"` | 无法获取的外部属性：`indeterminate` 交给 `strictFailure` 决定；`nomatch` 跳过该规则。`nomatch` 只在平台文档中能编译：与 [DENY](#external-attributes-and-onmissing) 不同，限流规则没有确认流程。 |
| `key` | 属性路径 | 一个共享桶 | String 或 Ip 属性，其值决定桶。`resource.var.<name>` 必须被每个资源模式捕获。 |
| `permitsPerSecond` | 数字 | 必填 | 大于 `0`。 |
| `windowSeconds` | 整数 | `1` | 至少为 `1`。 |
| `strictFailure` | 布尔 | `false` | 限流器无法计数时是否拒绝请求（`503`）。 |

规则按 DENY 语句的方式匹配：动作、资源和条件，使用它自己的路径变量；裸资源类型无法编译。

## 平台专属字段 {#platform-sections}

### 限制与属性 {#limits-and-attributes}

`limits` 设置所有文档编译时使用的[条件限制](#limits)。`attributes` 声明文档可以读取的外部属性：

```json
{ "path": "ext.geo.country", "type": "string" }
```

`path` 为 `ext.<provider>.<name>`；`type` 为 `string`、`bool`、`stringSet` 或 `ip`；每个路径只能出现一次。平台的某个修订改变了这两个字段之一时，每个实例都会重新编译应用和租户文档；不再能编译的文档失败即拒绝。控制面检查此类变更时会给出警告。

### 边界 {#boundaries}

边界限定一个租户最多能被允许什么。`"boundary": true` 的策略就是边界：只在平台文档中，只有 ALLOW 语句，至多 64 条，不可附加，不可绑定。`boundaries.default` 指定约束所有租户的边界；租户文档的 `boundary` 指定改为约束该租户的平台边界。`(platform)` 租户没有边界。此时 Permit 只有在租户边界中也有语句匹配时才成立；否则变为 Deny，若边界语句出错或指定的边界不存在则为 Indeterminate。

一个平台文档：

```json
{
  "schema": 2,
  "revision": 12,
  "publishedAt": "2026-11-01T08:00:00Z",
  "limits": { "maxCost": 128 },
  "attributes": [{ "path": "ext.geo.country", "type": "string" }],
  "boundaries": { "default": "boundary-standard" },
  "policies": [
    {
      "id": "deny-admin-outside-office",
      "statements": [
        {
          "effect": "deny",
          "action": "*",
          "resource": "http:path:/admin/**",
          "condition": { "not": { "cidr": ["environment.ip", ["10.0.0.0/8"]] } }
        }
      ]
    },
    {
      "id": "public-health",
      "statements": [{ "effect": "allow", "action": "http:GET", "resource": "http:path:/actuator/health" }]
    },
    {
      "id": "boundary-standard",
      "boundary": true,
      "statements": [{ "effect": "allow", "action": ["http:*", "order:*"] }]
    }
  ],
  "bindings": [
    { "id": "deny-admin", "subject": "*", "app": "*", "space": "*", "policy": "deny-admin-outside-office" },
    { "id": "health", "subject": "*", "app": "*", "space": "*", "policy": "public-health" },
    {
      "id": "ops-platform-admin",
      "subject": { "principal": "ops-1", "tenant": "(platform)" },
      "app": "*",
      "space": "*",
      "policy": "cosec:platform-admin"
    }
  ]
}
```

## 来源记录 {#provenance}

控制面和 `cosec-migrate` 记录每个修订是如何发布的。它不参与任何决策。

| 字段 | 含义 |
|---|---|
| `parent` | 被本修订替换的头修订的 `{"revision", "digest"}`（`digest`：40 位小写十六进制）；第一个修订没有。 |
| `change` | 发布它的变更。 |
| `kind` | `PUBLISH`、`ROLLBACK`、`BREAK_GLASS`、`EXPIRE`、`MIGRATE` 或 `BOOTSTRAP`。 |
| `publishedBy` | `{"tenant", "principal"}`。 |
| `approvedBy` | 变更需要审批时的审批人。 |
| `shadow` | `{"window", "skipped"}`：变更经过的影子窗口，或表示窗口被跳过。 |

## 发布文档 {#publishing-documents}

文档保存在 Redis 中，只能通过控制面（starter 的 `controlSupport`）或 `cosec-migrate publish` 和 `bootstrap` 写入，见[存储](../operations/storage.md)。控制面在发布前检查并编译每个变更，还可以先在线上流量上试运行：见[测试策略](./testing.md)。

## JSON Schema {#json-schema}

[`schema/`](https://github.com/Ahoo-Wang/CoSec/tree/main/schema) 中的 JSON Schema 可用于编辑器补全和 CI 检查：

| 文件 | 校验 |
|---|---|
| `cosec-snapshot-document.schema.json` | 平台、应用或租户文档 |
| `cosec-binding.schema.json` | 单个绑定 |
| `cosec-catalog.schema.json` | 应用的目录 |
| `definitions.schema.json` | 共享定义 |
| `problem.schema.json` | 错误响应体（[Web 集成](../integration/web.md)） |

文档是封闭的，所以不要在文档中加 `$schema` 字段：在编辑器中把 schema 映射到你的文件，例如在 VS Code 的 `settings.json` 中：

```json
{
  "json.schemas": [
    {
      "fileMatch": ["policies/*.json"],
      "url": "https://raw.githubusercontent.com/Ahoo-Wang/CoSec/refs/heads/main/schema/cosec-snapshot-document.schema.json"
    }
  ]
}
```

这些文件由 `cosec-core` 中的格式描述符（[`DocumentFormats`](https://github.com/Ahoo-Wang/CoSec/blob/main/core/src/main/kotlin/me/ahoo/cosec/snapshot/format/DocumentFormats.kt)）生成，从不手工编辑。有测试保证它们与文档读取器一致：一方接受的，另一方也接受。

通过 schema 校验是必要条件，而非充分条件。JSON Schema 无法表达的内容包括：文件是三种文档中的哪一种；id 唯一和文档之间的引用；时区是否存在，以及 2 月 30 日这样的日期；`from` 等于 `to` 的时间窗口，以及 `notBefore` 晚于 `notAfter`；路径变量的作用域；属性目录和条件操作数的类型。要检查这些，请编译文档（[测试策略](./testing.md#validating-policies)）。

**下一步：** 按角色而不是按主体授予权限，见[角色权限](./role-permissions.md)。

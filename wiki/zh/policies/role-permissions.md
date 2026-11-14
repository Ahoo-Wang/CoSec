---
title: "角色权限"
description: "在应用文档中描述应用的权限，并按应用和空间把它们授予角色。"
---

# 角色权限 {#role-permissions}

面向通过角色而不是逐个用户策略来管理访问的产品团队和管理员。本页介绍应用如何一次性描述它的权限、如何把权限授予角色，以及如何把授权限定在某个应用和某个空间。每个字段的定义见[策略语言](./language.md)。

应用发布一份**应用文档**：一份可做之事的目录、它的权限，以及授予这些权限的角色和角色绑定。目录条目可以自己定义权限（[简写权限](#short-form-permissions)），[角色](#roles)连同它持有的权限只写一次。主体的令牌（或 `MembershipProvider`）说明它在哪个空间拥有哪些角色；请求获得的，是角色绑定授予它在请求空间中所拥有角色的权限。租户还可以在自己的租户文档中添加授权。

## 应用文档 {#app-documents}

应用 `order` 的应用文档的键是 `app:order`。它包含[目录](#permission-catalogs)、[权限策略](#permission-policies)和[角色绑定](#granting-permissions-to-roles)，还可以包含[限流规则](./rate-limiting.md)；它不定义其他策略，除角色外不绑定任何主体。

```json
{
  "schema": 2,
  "revision": 1,
  "publishedAt": "2026-11-01T08:00:00Z",
  "catalog": {
    "groups": [
      {
        "name": "order",
        "description": "Orders",
        "spaced": true,
        "permissions": [
          { "id": "read", "name": "Read orders" },
          { "id": "ship", "name": "Ship orders" }
        ]
      }
    ]
  },
  "policies": [
    {
      "id": "perm:order/read",
      "statements": [{ "name": "read", "effect": "allow", "action": "order:read" }]
    },
    {
      "id": "perm:order/ship",
      "statements": [{ "name": "ship", "effect": "allow", "action": "order:ship", "resource": "order/{orderId}" }]
    }
  ],
  "bindings": [
    {
      "id": "viewer-read",
      "subject": { "role": "viewer" },
      "app": "order",
      "space": "*",
      "policy": "perm:order/read"
    },
    {
      "id": "clerk-ship",
      "subject": { "role": "clerk" },
      "app": "order",
      "space": "*",
      "policy": "perm:order/ship"
    }
  ]
}
```

当主体在请求空间中至少拥有一个角色，或者其租户的文档把该应用的某个权限策略绑定给 `Anyone` 或某个主体时（[租户角色绑定](#tenant-role-bindings)），请求读取其应用的应用文档。应用是凭证的应用，宿主有应用时必须是它：在网关上是路由元数据 `cosec-app-id`，否则是 `cosec.authorization.scope.app-id`（[应用](../integration/authentication.md#apps)）。谁能控制这些输入，见[安全模型](../guide/security-model.md#trust-boundaries)。没有应用的请求不读取任何应用文档，因此不会有应用的角色绑定适用于它。

## 权限目录 {#permission-catalogs}

`catalog` 把应用的权限按命名分组列出，供让管理员选择授予内容的控制台使用（[字段](./language.md#catalogs)）。写了 `action` 的条目同时定义这个权限，见[简写权限](#short-form-permissions)。其他条目不参与求值：没有什么经由它们授权，也没有检查确保它们有对应策略。`spaced` 说明简写权限在哪里生效。

## 权限策略 {#permission-policies}

每个权限是一个名为 `perm:<appId>/<permission>` 的策略，例如 `perm:order/read`。它的语句说明该权限允许什么，与其他策略相同。

- 只有应用自己的文档能定义它的权限策略，应用文档中的每个策略都是权限策略。
- 权限策略不能被附加：只能通过角色绑定到达。
- 平台文档和租户文档不定义权限策略。

## 把权限授予角色 {#granting-permissions-to-roles}

应用文档中的角色绑定把策略授予角色：

| 字段 | 在应用文档中 |
|---|---|
| `subject` | `{"role": "<roleId>"}` 表示任意租户中的该角色，`{"role": "<roleId>", "tenant": "<tenantId>"}` 表示某一个租户中的该角色。 |
| `app` | 应用自己的 id，或 `*`。限定到其他应用的绑定无法编译。 |
| `space` | 空间 id，`""` 表示默认空间，`*` 表示任意空间。 |
| `policy` | 应用的某个权限策略，或平台策略。其他应用的权限策略无法编译。 |

角色 id 与主体拥有的角色精确比较，区分大小写。

## 角色授权的作用域 {#role-grant-scope}

角色绑定在以下条件全部成立时适用于请求：

1. 主体在请求空间中拥有该角色（[空间](#spaces)）。
2. 绑定的 `app` 为 `*` 或请求的应用。
3. 绑定的 `space` 为 `*` 或请求的空间（`""` 为默认空间）。
4. 绑定没有指定 `tenant`，或指定的是主体的租户。
5. 绑定没有过期（`expiresAt`）。

| 绑定的 `space` | 适用于 |
|---|---|
| `""` | 仅默认空间 |
| `"team-a"` | 仅空间 `team-a` |
| `"*"` | 主体拥有该角色的每个空间 |

`*` 只扩大绑定适用的范围，不改变哪些角色计数：在默认空间拥有的角色不会在空间 `team-a` 中生效，即使通过 `space: "*"` 的绑定也一样。应用文档只为它自己的应用读取，所以在应用文档中 `app: "*"` 与应用自己的 id 含义相同。

## 空间 {#spaces}

请求的空间是 `CoSec-Space-Id` 参数；没有时为默认空间 `""`。主体的角色来自令牌的 `roles` 声明（或其 `MembershipProvider`）：`viewer` 是默认空间中的角色，`clerk@team-a` 是空间 `team-a` 中的角色（[认证](../integration/authentication.md)）。

| 令牌 `roles` | 请求空间 | 计数的角色 |
|---|---|---|
| `["viewer", "clerk@team-a"]` | 默认 | `viewer` |
| `["viewer", "clerk@team-a"]` | `team-a` | `clerk` |
| `["viewer", "clerk@team-a"]` | `team-b` | 无：请求被拒绝 |

请求声明了主体没有任何角色的空间时，以 `403`（`scope-rejected`）拒绝，绝不会退回默认空间求值；匿名请求声明空间时返回 `401`。`*` 永远不是空间 id。为什么可以从客户端获取空间，见[安全模型](../guide/security-model.md#trust-boundaries)。

## 租户的角色绑定 {#tenant-role-bindings}

租户在自己的租户文档中为角色授权，无需改动应用文档：

```json
{
  "schema": 2,
  "revision": 4,
  "publishedAt": "2026-11-01T08:00:00Z",
  "bindings": [
    {
      "id": "auditor-reads-orders",
      "subject": { "role": "auditor" },
      "app": "order",
      "space": "",
      "policy": "perm:order/read"
    }
  ]
}
```

- 没有 `tenant` 的角色主体是本租户的角色；指定其他租户无法编译。
- 策略可以是租户自己的策略、平台策略或应用的权限策略。应用的权限策略要求 `app` 恰好设为该应用：此时从该应用的文档中取得，并在同一请求中读取。权限策略的其他绑定方式都无法编译。
- 租户文档也可以把应用的权限策略绑定给 `Anyone` 或它自己的某个主体，同样限定到恰好该应用。这样，该应用中的请求无论主体有什么角色都会读取应用文档，于是绑定能够解析，其中的 DENY 或 ALLOW 生效；应用文档的[限流规则](./rate-limiting.md)也随之生效。
- 租户的角色绑定遵循相同的[作用域规则](#role-grant-scope)。

## 简写权限 {#short-form-permissions}

目录条目写了 `action`，就用一行定义了这个权限。它就是策略 `perm:<appId>/<id>`：一条 ALLOW 语句，动作和 `resource` 取自条目，不可附加。

```json
{
  "catalog": {
    "groups": [
      {
        "name": "仓库",
        "permissions": [{ "id": "warehouse.read", "name": "查看仓库", "action": "wms:warehouse.read" }]
      },
      {
        "name": "入库",
        "spaced": true,
        "permissions": [
          { "id": "inbound.ship", "name": "发货", "action": "wms:inbound.ship", "resource": "inbound/{id}" }
        ]
      }
    ]
  }
}
```

非 `spaced` 组里的权限只在默认空间生效：它的策略带条件 `{ "eq": ["scope.space", ""] }`。`spaced` 组里的权限在持有它的角色生效的任何空间都生效，默认空间也算一个空间。

- 一个权限只有一处定义：写了 `action` 的条目和同 id 的策略不能同时存在，否则无法编译。权限需要条件、DENY 或多条语句时，手写策略；`spaced` 对手写策略不起作用，限制由作者自己写。
- 动作必须是请求能解析出来的：[路由映射](./language.md#route-maps)、生成的 OpenAPI 映射，或代码里的检查。没有路由映射的服务可以直接写 HTTP：`"action": "http:GET"`、`"resource": "http:path:/orders/**"`。
- 既没有 `action` 也没有策略的条目和以前一样只用于显示，角色不能列出它。

## 角色 {#roles}

角色在文档里有自己的条目：id、名称、说明和它持有的权限，可以暂时没有权限。成员通过令牌的 `roles` 声明或 `MembershipProvider` 按 id 在某个空间持有角色，和其他角色一样。

应用文档保存应用的**系统角色**：

```json
{
  "roles": [
    { "id": "wms.clerk", "name": "仓库员", "description": "处理仓库作业", "permissions": ["warehouse.read"] },
    { "id": "wms.admin", "name": "管理员", "permissions": "*" },
    { "id": "wms.draft", "name": "待配置", "permissions": [] }
  ]
}
```

租户文档保存该租户的**自定义角色**，每个角色属于一个应用：

```json
{
  "roles": [
    { "id": "night-shift", "app": "wms", "name": "夜班", "permissions": ["warehouse.read"] },
    { "id": "wh-01-keeper", "app": "wms", "space": "wh-01", "name": "一号仓管理员", "permissions": ["inbound.ship"] }
  ]
}
```

| 字段 | 文档 | 含义 |
|---|---|---|
| `id` | 两种，必填 | 文档内唯一。不是 `*`，不含 `@`（成员关系里分隔角色和空间）。建议使用不透明 id。 |
| `app` | 租户，必填 | 角色所属的应用。角色只在自己的应用里授权。 |
| `space` | 租户，可选 | 不写：成员持有该角色的任何空间。写了就只在那个空间，`""` 是默认空间。不能是保留 id。 |
| `name`、`description` | 两种，可选 | 用于显示。 |
| `permissions` | 两种，必填 | 目录 id，可以为空。`"*"` 表示目录定义的所有权限，只能写在应用文档里。 |

- 系统角色对所有租户生效，在成员持有它的任何空间生效。它不指定空间：空间属于租户。
- `"*"` 跟随目录：之后新增的权限也归这个角色，变更的差异和审批里表现为新增的绑定。租户的角色不能写 `"*"`，否则应用的一次发布会悄悄扩大每个租户的角色。
- 列出未定义的权限，在应用文档里无法编译。租户文档编译时看不到应用文档，所以由控制面报告（`REFERENCES`）；数据面上，权限已不存在的角色是 indeterminate，请求失败即拒绝。
- 指定了空间的角色列出非 `spaced` 组的权限时有告警：这项权限在那个空间永远不生效。租户角色的 id 与其应用的系统角色相同时也有告警：持有它的成员得到两者的并集。
- 角色没有 `expiresAt`，不是 break-glass，也不能绑定主体或 `Anyone`：这些情况写[绑定](#granting-permissions-to-roles)。两种写法可以授予同一个角色，结果取并集。

角色不以绑定形式存储。所有读取文档授权含义的地方都先展开它：每个权限一条绑定，id 为 `role:<roleId>@<app>:<permission>`，排在手写绑定之后。编译、授出分析、引用索引、差异、`simulate`、`explain` 和 `analyze` 都读取展开结果。一份文档的角色最多派生 **50,000** 条绑定，超过则无法编译，错误位于 `$.roles`。

### 授权者需要什么 {#what-a-grantor-needs}

`putRole` 需要对该角色（`doc/tenant/<t>/role/<id>`）的 `cosec:write`。它新增派生绑定时，还需要对它唯一作用域的 `cosec:grant`；`removeRole` 和只减少权限的 `putRole` 只需要 `cosec:write`。让 `"*"` 角色派生出新绑定的 `putCatalog` 或 `putPolicy` 同样需要这项 `cosec:grant`：看的是角色实际派生了什么，而不是由哪个操作造成的。因此管理员可以比 `cosec:tenant-admin` 更窄：

| 管理员 | `cosec:write` | `cosec:grant` |
|---|---|---|
| 权限配置员 | `doc/tenant/<t>/role/*` | `scope/<t>/*/*` |
| 空间管理员 | `doc/tenant/<t>/role/*` | `scope/<t>/wms/wh-01` |

[授出分析](./testing.md)按权限拆分角色，`NOT_COVERED` 指出它无法证明的那项权限：

| 角色授出的 | 作用域 | 授权者需要 |
|---|---|---|
| 非 `spaced` 权限 | 默认空间（条件收窄） | 在默认空间持有该权限 |
| 指定了空间 `S` 的角色的任意权限 | `S` | 在 `S` 持有该权限 |
| 未指定空间的角色的 `spaced` 权限 | 所有空间 | 在所有空间都持有：经附加策略或 `space: "*"` 的主体绑定 |

经角色持有的权限只在持有该角色的空间里算，永远覆盖不了“所有空间”，所以第三行的结果是 `UNKNOWN`，走审批。

租户 Owner 最适合用附加策略表达：令牌的 `policies` 声明一条可附加的策略，授出分析按所有应用、所有空间计。谁成为 Owner 由身份系统决定，不需要修改文档。

## 从外部事实源同步角色 {#syncing-roles-from-an-external-source}

角色和权限的事实源可以在 CoSec 之外，例如一个事件溯源的身份系统。这时 CoSec 的文档是下游投影，**仍然只经控制面写入**：直接写缓存会绕过校验、编译和审计。

| 来源的变化 | 控制面调用 |
|---|---|
| 角色的权限变化 | `putRole`，不带 `ifMatch` |
| 角色删除 | `removeRole` |
| 应用的权限变化 | `putCatalog` |
| 定期对账 | `apply`，`MERGE`；来源是唯一写入者时用 `REPLACE` |

- 每次变更走 `propose`、`skip-shadow`、`publish`。调用者是持有 `cosec:approve`、`cosec:skip-shadow` 和 `cosec:publish` 的平台服务主体，即信任根（[测试策略](./testing.md)）。谁能授予由来源系统判断，CoSec 记录审计。
- `putRole` 整体替换角色，重放是幂等的。冲突（`409`）由投影重试。
- `skip-shadow` 要占用文档的暂存槽，而别人正在影子运行的变更占着它：同一份文档不要同时有人工变更和同步。

## 存储目录和授权 {#storing-catalogs-and-grants}

应用文档属于平台：只有 `(platform)` 租户的主体能修改它们，租户的主体只能修改自己的租户文档。通过控制面发布文档，可以整份提交（`POST /documents/app:order:apply`），也可以用条目操作（`putCatalog`、`putPolicy`、`putBinding`、`putRole`）；首个修订也可以用 `cosec-migrate publish` 写入。变更在发布前会被检查、编译，并可以先影子运行：见[测试策略](./testing.md)和[存储](../operations/storage.md)。

## 从 OpenAPI 生成目录 {#generating-a-catalog-from-openapi}

启用 `cosec-openapi` 和 springdoc 后，`cosecServiceGenerator` actuator 端点从 springdoc 扫描到的 OpenAPI 文档生成两部分配置：

- `cosecServiceGenerator/routes`：[路由映射](./language.md#route-maps)。
- `cosecServiceGenerator/app`：应用文档。

服务名取 `info.x-cosec-service`，否则取 `info.title`。每个操作的动作是 `<service>:<operationId>`，或它的 `x-cosec-action`。它的资源是 `<service>/{<variable>}/...`，按顺序包含路径的每个变量（`/users/{id}/avatar/{size}` 是 `user/{id}/{size}`；没有变量时为 `<service>`），或它的 `x-cosec-resource`。它的路径是文档 `servers` 的路径加上操作的路径，不含上下文路径（`server.servlet.context-path`、`spring.webflux.base-path`），与 CoSec 判定请求时一致：服务器 `https://api.example.com/api/v1` 加路径 `/orders/{id}` 是路由 `/api/v1/orders/{id}`。多个服务器路径不同，或服务器路径含服务器变量时，生成失败：无法判断宿主服务的是哪一个。应用文档是一个目录，按操作的第一个标签分组（没有标签时为 `default`），每个动作有一个[简写权限](#short-form-permissions) `<verb>`，允许该动作作用于任意资源（即策略 `perm:<service>/<verb>`），不需要手写策略。生成的文档没有角色，也没有绑定：添加角色或绑定、评审文档，然后按上文发布。分组不是 `spaced`，所以权限只在默认空间生效：使用空间的应用要在分组上设置 `"spaced": true`（[使用空间的应用](../scenarios/spaces.md)）。操作缺少动作、`operationId` 重复，或两个操作可能匹配同一请求时，生成失败。

路径的 Path Item 是 `$ref`（OpenAPI 3.1）时，生成结果与它所指的 Path Item 相同：指向 `#/components/pathItems/<name>` 的引用会被解析，引用链也会被跟随。目标不存在、引用成环、引用指向别处（另一个文件、URL、文档的其他位置），或该路径在 `$ref` 之外还有自己的操作时，生成失败，并指出路径和引用：OpenAPI 规范没有定义这种组合，丢掉或猜测某个操作的路由映射，授予的权限会比 API 描述的更少或更多。

本页内容的完整示例，文档均可运行：[系统角色与自定义角色](../scenarios/roles.md)、[使用空间的应用](../scenarios/spaces.md)、[租户所有者](../scenarios/tenant-owner.md)和[外部事实源同步角色](../scenarios/external-source.md)。

**下一步：** 限制已允许的请求能以多高的频率执行，见[限流](./rate-limiting.md)。
